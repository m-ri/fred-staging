/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.MultiValueTable;
import freenet.support.URLEncoder;
import freenet.support.api.HTTPRequest;

/**
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public abstract class LocalFileBrowserToadlet extends Toadlet {	
	private final NodeClientCore core;
	private File currentPath;

	public LocalFileBrowserToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
		super(highLevelSimpleClient);
		this.core = core;
	}
	
	public abstract String path();
	
	protected abstract String postTo();
	
	/**
	 * Performs sanity checks and generates parameter persistence.
	 * Must be called before using createHiddenParamFields or createDirectoryButton
	 * because it returns hiddenFieldName and HiddenFieldValue pairs.
	 */
	protected abstract ArrayList<ArrayList<String>> processParams(HTTPRequest request);
	
	protected final ArrayList<String> makePair(String one, String two){
		ArrayList<String> pair = new ArrayList<String>();
		pair.add(one);
		pair.add(two);
		return pair;
	}
	
	protected void createInsertDirectoryButton(HTMLNode fileRow, String path, ToadletContext ctx, ArrayList<ArrayList<String>> fieldPairs) {
		HTMLNode cellNode = fileRow.addChild("td");
		HTMLNode formNode = ctx.addFormChild(cellNode, postTo(), "insertLocalFileForm");
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local-dir", l10n("insert")});
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "filename", path});
		createHiddenParamFields(formNode, fieldPairs);
	}
	
	/**
	 * Renders hidden fields on given fieldNode. 
	 * hiddenFieldName and hiddenFieldValue must have the same number of elements.
	 */
	private final void createHiddenParamFields(HTMLNode formNode, ArrayList<ArrayList<String>> fieldPairs){
		for(ArrayList<String> pair : fieldPairs)
		{
			assert(pair.size() == 2);
			formNode.addChild("input", new String[] { "type", "name", "value" }, 
					new String[] { "hidden", pair.get(0), pair.get(1)});
		}
		return;
	}
	
	private final void createChangeDirButton(HTMLNode formNode, String buttonText, String path, ArrayList<ArrayList<String>> fieldPairs){
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "change-dir", buttonText});
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "path", path});
		createHiddenParamFields(formNode, fieldPairs);
	}

	// FIXME reentrancy issues with currentPath - fix running two at once.
	/**
	 * @see freenet.clients.http.Toadlet#handleGet(java.net.URI,
	 *      freenet.clients.http.ToadletContext)
	 */
	public void handleMethodPOST(URI uri, HTTPRequest request, final ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		renderPage(request, ctx);
	}
	
	private void renderPage(HTTPRequest request, final ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		ArrayList<ArrayList<String>> fieldPairs = processParams(request);
		// FIXME: What is a good maximum path length?
		String path = request.getPartAsStringFailsafe("path", 4096);
		if (path.length() == 0) {
			if (currentPath == null) {
				currentPath = new File(System.getProperty("user.home")); // FIXME what if user.home is denied?
			}
			path = currentPath.getCanonicalPath();
		}

		File thisPath = new File(path).getCanonicalFile();
		
		PageMaker pageMaker = ctx.getPageMaker();

		if(!core.allowUploadFrom(thisPath)) {
			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", thisPath.getAbsolutePath()), ctx);
			pageMaker.getInfobox("infobox-error",  "Forbidden", page.content, "access-denied", true).
				addChild("#", l10n("dirAccessDenied"));

			thisPath = currentPath;
			if(!core.allowUploadFrom(thisPath)) {
				File[] allowedDirs = core.getAllowedUploadDirs();
				if(allowedDirs.length == 0) {
					sendErrorPage(ctx, 403, "Forbidden", l10n("dirAccessDenied"));
					return;
				} else {
					thisPath = allowedDirs[core.node.fastWeakRandom.nextInt(allowedDirs.length)];
				}
			}
		}
		
		if(currentPath == null)
			currentPath = thisPath;
		
		HTMLNode pageNode;

		if (thisPath.exists() && thisPath.isDirectory() && thisPath.canRead()) {
			// Known safe at this point
			currentPath = thisPath;

			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", currentPath.getAbsolutePath()), ctx);
			pageNode = page.outer;
			HTMLNode contentNode = page.content;
			if(ctx.isAllowedFullAccess())
				contentNode.addChild(core.alerts.createSummary());
			
			HTMLNode infoboxDiv = contentNode.addChild("div", "class", "infobox");
			infoboxDiv.addChild("div", "class", "infobox-header", l10n("listing", "path",  currentPath.getAbsolutePath()));
			HTMLNode listingDiv = infoboxDiv.addChild("div", "class", "infobox-content");
			
			File[] files = currentPath.listFiles();
			
			if(files == null) {
				File home = new File(System.getProperty("user.home")); // FIXME what if user.home is denied?
				if(home.equals(currentPath)) {
					sendErrorPage(ctx, 403, "Forbidden", l10n("dirAccessDenied"));
					return;
				}
				sendErrorPage(ctx, 403, "Forbidden", l10n("dirAccessDenied"));
				currentPath = home;
				renderPage(request, ctx);
				return;
			}
			
			Arrays.sort(files, new Comparator<File>() {
				public int compare(File firstFile, File secondFile) {
					if (firstFile.isDirectory() && !secondFile.isDirectory()) {
						return -1;
					}
					if (!firstFile.isDirectory() && secondFile.isDirectory()) {
						return 1;
					}
					return firstFile.getName().compareToIgnoreCase(secondFile.getName());
				}
			});
			HTMLNode listingTable = listingDiv.addChild("table");
			HTMLNode headerRow = listingTable.addChild("tr");
			headerRow.addChild("th");
			headerRow.addChild("th", l10n("fileHeader"));
			headerRow.addChild("th", l10n("sizeHeader"));
			/* add filesystem roots (fsck windows) */
			File[] roots = File.listRoots();
			for (int rootIndex = 0, rootCount = roots.length; rootIndex < rootCount; rootIndex++) {
				File currentRoot = roots[rootIndex];
				HTMLNode rootRow = listingTable.addChild("tr");
				rootRow.addChild("td");
				HTMLNode rootLinkCellNode = rootRow.addChild("td");
				HTMLNode rootLinkFormNode = ctx.addFormChild(rootLinkCellNode, path(), "insertLocalFileForm");
				createChangeDirButton(rootLinkFormNode, currentRoot.getCanonicalPath(), currentRoot.getAbsolutePath(), fieldPairs);
				rootRow.addChild("td");
			}
			/* add back link */
			if (currentPath.getParent() != null) {
				HTMLNode backlinkRow = listingTable.addChild("tr");
				backlinkRow.addChild("td");
				HTMLNode backLinkCellNode = backlinkRow.addChild("td");
				HTMLNode backLinkFormNode = ctx.addFormChild(backLinkCellNode, path(), "insertLocalFileForm");
				createChangeDirButton(backLinkFormNode, "..", currentPath.getParent(), fieldPairs);
				backlinkRow.addChild("td");
			}
			for (int fileIndex = 0, fileCount = files.length; fileIndex < fileCount; fileIndex++) {
				File currentFile = files[fileIndex];
				HTMLNode fileRow = listingTable.addChild("tr");
				if (currentFile.isDirectory()) {
					if (currentFile.canRead()) {
						createInsertDirectoryButton(fileRow, currentFile.getAbsolutePath(), ctx, fieldPairs);
						
						// Change directory
						HTMLNode directoryCellNode = fileRow.addChild("td");
						HTMLNode directoryFormNode = ctx.addFormChild(directoryCellNode, path(), "insertLocalFileForm");
						createChangeDirButton(directoryFormNode, currentFile.getName(), currentFile.getAbsolutePath(), fieldPairs);
					} else {
						fileRow.addChild("td");
						fileRow.addChild("td", "class", "unreadable-file", currentFile.getName());
					}
					fileRow.addChild("td");
				} else {
					if (currentFile.canRead()) {
						
						// Insert file
						HTMLNode cellNode = fileRow.addChild("td");
						HTMLNode formNode = ctx.addFormChild(cellNode, postTo(), "insertLocalFileForm");
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local-file", l10n("insert")});
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "filename", currentFile.getAbsolutePath()});
						createHiddenParamFields(formNode, fieldPairs);
						
						fileRow.addChild("td", currentFile.getName());
						fileRow.addChild("td", "class", "right-align", String.valueOf(currentFile.length()));
					} else {
						fileRow.addChild("td");
						fileRow.addChild("td", "class", "unreadable-file", currentFile.getName());
						fileRow.addChild("td", "class", "right-align", String.valueOf(currentFile.length()));
					}
				}
			}
		} else {
			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", currentPath.getAbsolutePath()), ctx);
			pageNode = page.outer;
			HTMLNode contentNode = page.content;
			if(ctx.isAllowedFullAccess())
				contentNode.addChild(core.alerts.createSummary());
			
			HTMLNode infoboxDiv = contentNode.addChild("div", "class", "infobox");
			infoboxDiv.addChild("div", "class", "infobox-header", l10n("listing", "path",  currentPath.getAbsolutePath()));
			HTMLNode listingDiv = infoboxDiv.addChild("div", "class", "infobox-content");

			listingDiv.addChild("#", l10n("dirCannotBeRead", "path", currentPath.getAbsolutePath()));
			HTMLNode ulNode = listingDiv.addChild("ul");
			ulNode.addChild("li", l10n("checkPathExist"));
			ulNode.addChild("li", l10n("checkPathIsDir"));
			ulNode.addChild("li", l10n("checkPathReadable"));
		}
		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("LocalFileInsertToadlet."+key, new String[] { pattern }, new String[] { value });
	}

	private String l10n(String msg) {
		return NodeL10n.getBase().getString("LocalFileInsertToadlet."+msg);
	}
}