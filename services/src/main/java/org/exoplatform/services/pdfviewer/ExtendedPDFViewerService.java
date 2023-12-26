package org.exoplatform.services.pdfviewer;

import org.apache.commons.lang3.StringUtils;
import org.artofsolving.jodconverter.office.OfficeException;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.cache.CacheService;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cms.impl.Utils;
import org.exoplatform.services.cms.mimetype.DMSMimeTypeResolver;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import javax.jcr.Node;
import java.io.*;

public class ExtendedPDFViewerService extends org.exoplatform.services.pdfviewer.PDFViewerService {

  private static final Log                                              LOG        =
                                                                            ExoLogger.getLogger(PDFViewerService.class.getName());

  private CacheService                                                  cacheService;

  private static final String                                           CACHE_NAME = "ecms.PDFViewerService";

  private ExoCache<Serializable, Object>                                pdfCache;

  private org.exoplatform.services.cms.jodconverter.JodConverterService jodConverterService;

  public ExtendedPDFViewerService(RepositoryService repositoryService,
                          CacheService caService,
                          org.exoplatform.services.cms.jodconverter.JodConverterService jodConverterService,
                          InitParams initParams)
      throws Exception {
    super(repositoryService, caService, initParams);
    this.jodConverterService = jodConverterService;
    pdfCache = caService.getCacheInstance(CACHE_NAME);
  }

  /**
   * Write PDF data to file
   *
   * @param currentNode
   * @param repoName
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
    String lastModifiedTime = (String) pdfCache.get(new ObjectKey(bd1.toString()));
    String cachedBaseVersion = (String) pdfCache.get(new ObjectKey(bd2.toString()));
    File content = null;
    String name = currentNode.getName().replaceAll(":", "_");
    Node contentNode = currentNode.getNode("jcr:content");
    String lastModified = Utils.getJcrContentLastModified(currentNode);
    String baseVersion = Utils.getJcrContentBaseVersion(currentNode);
    if (path == null || !(content = new File(path)).exists() || !lastModified.equals(lastModifiedTime)
        || !StringUtils.equals(baseVersion, cachedBaseVersion)) {
      String mimeType = contentNode.getProperty("jcr:mimeType").getString();
      InputStream input = new BufferedInputStream(contentNode.getProperty("jcr:data").getStream());
      // Create temp file to store converted data of nt:file node
      if (name.indexOf(".") > 0)
        name = name.substring(0, name.lastIndexOf("."));
      // cut the file name if name is too long, because OS allows only file with
      // name < 250 characters
      name = reduceFileNameSize(name);
      content = File.createTempFile(name + "_tmp", ".pdf");
      // Convert to pdf if need
      String extension = DMSMimeTypeResolver.getInstance().getExtension(mimeType);
      if ("pdf".equals(extension)) {
        read(input, new BufferedOutputStream(new FileOutputStream(content)));
      } else {
        // create temp file to store original data of nt:file node
        File in = File.createTempFile(name + "_tmp", "." + extension);
        read(input, new BufferedOutputStream(new FileOutputStream(in)));
        long fileSize = in.length(); // size in byte
        if (LOG.isDebugEnabled()) {
          LOG.debug("File '" + currentNode.getPath() + "' of " + fileSize + " B. Size limit for preview: "
              + (getMaxFileSize() / (1024 * 1024)) + " MB");
        }
        if (fileSize <= getMaxFileSize()) {
          try {
            boolean success = jodConverterService.convert(in, content, "pdf");
            // If the converting failed then delete the content of temporary
            // file
            if (!success) {
              content.delete();
              content = null;
            }

          } catch (OfficeException connection) {
            content.delete();
            content = null;
            if (LOG.isErrorEnabled()) {
              LOG.error("Exception when using Office Service", connection);
            }
          } finally {
            in.delete();
          }
        } else {
          LOG.info("File '" + currentNode.getPath() + "' is too big for preview.");
          content.delete();
          content = null;
          in.delete();
        }
      }
      if (content.exists()) {
        if (contentNode.hasProperty("jcr:lastModified")) {
          pdfCache.put(new ObjectKey(bd.toString()), content.getPath());
          pdfCache.put(new ObjectKey(bd1.toString()), lastModified);
        }
        pdfCache.put(new ObjectKey(bd2.toString()), baseVersion);
      }
    }
    return content;
  }

  private String reduceFileNameSize(String name) {
    return name != null && name.length() > 150 ? name.substring(0, 150) : name;
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
