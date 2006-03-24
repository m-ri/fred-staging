package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;

import freenet.client.ArchiveContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;

/**
 * A high level data request.
 */
public class ClientGetter extends ClientRequester implements GetCompletionCallback {

	final ClientCallback client;
	final FreenetURI uri;
	final FetcherContext ctx;
	final ArchiveContext actx;
	ClientGetState currentState;
	private boolean finished;
	private int archiveRestarts;
	/** If not null, Bucket to return the data in */
	final Bucket returnBucket;

	/**
	 * Fetch a key.
	 * @param client
	 * @param sched
	 * @param uri
	 * @param ctx
	 * @param priorityClass
	 * @param clientContext The context object (can be anything). Used for round-robin query balancing.
	 * @param returnBucket The bucket to return the data in. Can be null. If not null, the ClientGetter must either
	 * write the data directly to the bucket, or copy it and free the original temporary bucket. Preferably the
	 * former, obviously!
	 */
	public ClientGetter(ClientCallback client, ClientRequestScheduler chkSched, ClientRequestScheduler sskSched, FreenetURI uri, FetcherContext ctx, short priorityClass, Object clientContext, Bucket returnBucket) {
		super(priorityClass, chkSched, sskSched, clientContext);
		this.client = client;
		this.returnBucket = returnBucket;
		this.uri = uri;
		this.ctx = ctx;
		this.finished = false;
		this.actx = new ArchiveContext();
		archiveRestarts = 0;
	}
	
	public void start() throws FetchException {
		try {
			currentState = SingleFileFetcher.create(this, this, new ClientMetadata(),
					uri, ctx, actx, ctx.maxNonSplitfileRetries, 0, false, null, true,
					returnBucket);
			if(currentState != null)
				currentState.schedule();
		} catch (MalformedURLException e) {
			throw new FetchException(FetchException.INVALID_URI, e);
		}
	}

	public void onSuccess(FetchResult result, ClientGetState state) {
		finished = true;
		currentState = null;
		if(returnBucket != null && result.asBucket() != returnBucket) {
			Bucket from = result.asBucket();
			Bucket to = returnBucket;
			try {
				Logger.minor(this, "Copying - returnBucket not respected by client.async");
				BucketTools.copy(from, to);
				from.free();
			} catch (IOException e) {
				Logger.error(this, "Error copying from "+from+" to "+to+" : "+e.toString(), e);
				onFailure(new FetchException(FetchException.BUCKET_ERROR, e.toString()), state /* not strictly to blame, but we're not ako ClientGetState... */);
			}
			result = new FetchResult(result, to);
		} else {
			if(returnBucket != null)
				Logger.minor(this, "client.async returned data in returnBucket");
		}
		client.onSuccess(result, this);
	}

	public void onFailure(FetchException e, ClientGetState state) {
		while(true) {
			if(e.mode == FetchException.ARCHIVE_RESTART) {
				archiveRestarts++;
				if(archiveRestarts > ctx.maxArchiveRestarts)
					e = new FetchException(FetchException.TOO_MANY_ARCHIVE_RESTARTS);
				else {
					try {
						start();
					} catch (FetchException e1) {
						e = e1;
						continue;
					}
					return;
				}
			}
			finished = true;
			client.onFailure(e, this);
			return;
		}
	}
	
	public void cancel() {
		Logger.minor(this, "Cancelling "+this);
		synchronized(this) {
			super.cancel();
			if(currentState != null) {
				Logger.minor(this, "Cancelling "+currentState);
				currentState.cancel();
			}
		}
	}

	public boolean isFinished() {
		return finished || cancelled;
	}

	public FreenetURI getURI() {
		return uri;
	}

	public void notifyClients() {
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.blockSetFinalized));
	}

	public void onBlockSetFinished(ClientGetState state) {
		Logger.minor(this, "Set finished", new Exception("debug"));
		blockSetFinalized();
	}

}
