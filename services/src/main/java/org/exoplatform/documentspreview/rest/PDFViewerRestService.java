package org.exoplatform.documentspreview.rest;

import org.apache.commons.lang3.StringUtils;
import org.artofsolving.jodconverter.office.OfficeException;
import org.exoplatform.services.cache.CacheService;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cms.impl.Utils;
import org.exoplatform.services.cms.jodconverter.JodConverterService;
import org.exoplatform.services.cms.mimetype.DMSMimeTypeResolver;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.pdfviewer.ObjectKey;
import org.exoplatform.services.pdfviewer.PDFViewerService;
import org.exoplatform.services.wcm.utils.WCMCoreUtils;
import org.exoplatform.wcm.connector.viewer.PDFViewerRESTService;

import javax.jcr.Node;
import javax.ws.rs.Path;
import java.io.*;

@Path("/pdfviewer/{repoName}/")
public class PDFViewerRestService extends PDFViewerRESTService {

  private static final Log LOG  = ExoLogger.getLogger(PDFViewerRESTService.class.getName());

  private static final String PDF_VIEWER_CACHE = "ecms.PDFViewerRestService";
  private RepositoryService repositoryService_;

  private JodConverterService jodConverterService;

  private ExoCache<Serializable, Object> pdfCache;

  public PDFViewerRestService(RepositoryService repositoryService,
                              JodConverterService jodConverterService,
                              CacheService caService) throws Exception {
    super(repositoryService, caService);
    PDFViewerService pdfViewerService = WCMCoreUtils.getService(PDFViewerService.class);
    this.jodConverterService = jodConverterService;
    if(pdfViewerService != null){
      pdfCache = pdfViewerService.getCache();
    }else{
      pdfCache = caService.getCacheInstance(PDF_VIEWER_CACHE);
    }
  }

  /**
   * Writes PDF data to file.
   * @param currentNode The name of the current node.
   * @param repoName The repository name.
   * @return
   * @throws Exception
   */
  public File getPDFDocumentFile(Node currentNode, String repoName) throws Exception {
    String wsName = currentNode.getSession().getWorkspace().getName();
    String uuid = currentNode.getUUID();
    StringBuilder bd = new StringBuilder();
    StringBuilder bd1 = new StringBuilder();
    StringBuilder bd2 = new StringBuilder();
    bd.append(repoName).append("/").append(wsName).append("/").append(uuid);
    bd1.append(bd).append("/jcr:lastModified");
    bd2.append(bd).append("/jcr:baseVersion");
    String path = (String) pdfCache.get(new ObjectKey(bd.toString()));
    String lastModifiedTime = (String)pdfCache.get(new ObjectKey(bd1.toString()));
    String baseVersion = (String)pdfCache.get(new ObjectKey(bd2.toString()));
    File content = null;
    String name = currentNode.getName().replace(":","_");
    Node contentNode = currentNode.getNode("jcr:content");
    String lastModified = getJcrLastModified(currentNode);
    String jcrBaseVersion = getJcrBaseVersion(currentNode);
    if (path == null || !(content = new File(path)).exists() || !lastModified.equals(lastModifiedTime) ||
            !StringUtils.equals(baseVersion, jcrBaseVersion)) {
      String mimeType = contentNode.getProperty("jcr:mimeType").getString();
      InputStream input = new BufferedInputStream(contentNode.getProperty("jcr:data").getStream());
      // Create temp file to store converted data of nt:file node
      if (name.indexOf(".") > 0) name = name.substring(0, name.lastIndexOf("."));
      // cut the file name if name is too long, because OS allows only file with name < 250 characters
      name = reduceFileNameSize(name);
      content = File.createTempFile(name + "_tmp", ".pdf");
      /*
      file.deleteOnExit();
        PM Comment : I removed this line because each deleteOnExit creates a reference in the JVM for future removal
        Each JVM reference takes 1KB of system memory and leads to a memleak
      */
      // Convert to pdf if need
      String extension = DMSMimeTypeResolver.getInstance().getExtension(mimeType);
      if ("pdf".equals(extension)) {
        read(input, new BufferedOutputStream(new FileOutputStream(content)));
      } else {
        // create temp file to store original data of nt:file node
        File in = File.createTempFile(name + "_tmp", "." + extension);
        read(input, new BufferedOutputStream(new FileOutputStream(in)));
        try {
          boolean success = jodConverterService.convert(in, content, "pdf");
          // If the converting was failure then delete the content temporary file
          if (!success) {
            content.delete();
          }
        } catch (OfficeException connection) {
          content.delete();
          if (LOG.isErrorEnabled()) {
            LOG.error("Exception when using Office Service");
          }
        } finally {
          in.delete();
        }
      }
      if (content.exists()) {
        pdfCache.put(new ObjectKey(bd.toString()), content.getPath());
        pdfCache.put(new ObjectKey(bd1.toString()), lastModified);
        pdfCache.put(new ObjectKey(bd2.toString()), jcrBaseVersion);
      }
    }
    return content;
  }

  private String reduceFileNameSize(String name) {
    return name != null && name.length() > 150 ? name.substring(0, 150) : name;
  }

  private String getJcrLastModified(Node node) throws Exception {
    Node checkedNode = node;
    if (node.isNodeType("nt:frozenNode")) {
      checkedNode = node.getSession().getNodeByUUID(node.getProperty("jcr:frozenUuid").getString());
    }
    return Utils.getJcrContentLastModified(checkedNode);
  }

  private String getJcrBaseVersion(Node node) throws Exception {
    Node checkedNode = node;
    if (node.isNodeType("nt:frozenNode")) {
      checkedNode = node.getSession().getNodeByUUID(node.getProperty("jcr:frozenUuid").getString());
    }
    return checkedNode.hasProperty("jcr:baseVersion") ? checkedNode.getProperty("jcr:baseVersion").getString() : null;
  }

  private void read(InputStream is, OutputStream os) throws Exception {
    int bufferLength = 1024;
    int readLength = 0;
    while (readLength > -1) {
      byte[] chunk = new byte[bufferLength];
      readLength = is.read(chunk);
      if (readLength > 0) {
        os.write(chunk, 0, readLength);
      }
    }
    os.flush();
    os.close();
  }
}
