/**
 * Copyright (C) 2026 eXo Platform SAS
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.addons.documentspreview;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.artofsolving.jodconverter.office.OfficeException;
import org.icepdf.core.pobjects.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.documents.service.DocumentFileService;
import org.exoplatform.services.cache.CacheListener;
import org.exoplatform.services.cache.CacheListenerContext;
import org.exoplatform.services.cache.CacheService;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cms.jodconverter.JodConverterService;
import org.exoplatform.services.cms.mimetype.DMSMimeTypeResolver;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.portal.thumbnail.model.FileContent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/documentspreview")
@Tag(name = "/documentspreview", description = "Provides the content of a document to preview")
public class DocumentsPreviewRest {

  private static final Log LOG = ExoLogger.getLogger(DocumentsPreviewRest.class);

  private static final Set<String> OFFICE_MIME_TYPES = Set.of("application/msword",
                                                               "application/ppt",
                                                               "application/vnd.ms-powerpoint",
                                                               "application/rtf",
                                                               "application/vnd.oasis.opendocument.graphics",
                                                               "application/vnd.oasis.opendocument.presentation",
                                                               "application/vnd.oasis.opendocument.spreadsheet",
                                                               "application/vnd.oasis.opendocument.spreadsheet-template",
                                                               "application/vnd.oasis.opendocument.text",
                                                               "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                                               "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                                               "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                                               "application/vnd.openxmlformats-officedocument.presentationml.template",
                                                               "application/vnd.openxmlformats-officedocument.wordprocessingml.document.form",
                                                               "application/vnd.sun.xml.impress",
                                                               "application/vnd.sun.xml.writer",
                                                               "application/wordperfect",
                                                               "application/xls",
                                                               "application/vnd.ms-excel",
                                                               "application/xlt",
                                                               "text/csv",
                                                               "application/vnd.oasis.opendocument.formula");

  private static final String   PDF_CACHE_NAME = "documentspreview.PDFCache";

  private static final String   MAX_FILE_SIZE_PROPERTY_NAME = "exo.documents.preview.max-file-size";

  private static final String   MAX_PAGES_PROPERTY_NAME = "exo.documents.preview.max-pages";

  private static final long     DEFAULT_MAX_FILE_SIZE_MB = 10;

  private static final long     DEFAULT_MAX_PAGES = 100;

  @Autowired
  private DocumentFileService   documentFileService;

  @Autowired
  private JodConverterService   jodConverterService;

  @Autowired
  private CacheService          cacheService;

  private ExoCache<String, String> pdfCache;

  @PostConstruct
  public void init() {
    pdfCache = cacheService.getCacheInstance(PDF_CACHE_NAME);
    pdfCache.addCacheListener(new CacheListener<String, String>() {
      public void onExpire(CacheListenerContext context, String key, String path) throws Exception {
        FileUtils.deleteQuietly(new File(path));
      }

      public void onRemove(CacheListenerContext context, String key, String path) throws Exception {
        FileUtils.deleteQuietly(new File(path));
      }

      public void onPut(CacheListenerContext context, String key, String path) throws Exception {
        // Nothing to do on insertion
      }

      public void onGet(CacheListenerContext context, String key, String path) throws Exception {
        // Nothing to do on retrieval
      }

      public void onClearCache(CacheListenerContext context) throws Exception {
        // Individual cached PDF files are cleaned up via onExpire/onRemove
      }
    });
  }

  @GetMapping("/{id}")
  @Secured("users")
  @Operation(summary = "Gets the content of a document by its identifier", method = "GET", description = "Gets the content of a document by its identifier")
  @ApiResponses(value = {
    @ApiResponse(responseCode = "200", description = "Request fulfilled"),
    @ApiResponse(responseCode = "400", description = "Invalid query input"),
    @ApiResponse(responseCode = "404", description = "Resource not found"),
    @ApiResponse(responseCode = "413", description = "Document exceeds the maximum size or page count allowed for preview"),
    @ApiResponse(responseCode = "500", description = "Internal server error"),
    @ApiResponse(responseCode = "503", description = "Document conversion service is currently unavailable") })
  public ResponseEntity<InputStreamResource> getDocumentContent(HttpServletRequest request,
                                                                @Parameter(description = "Document technical identifier", required = true)
                                                                @PathVariable("id")
                                                                String id) {
    if (StringUtils.isBlank(id)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "document_id_is_mandatory");
    }
    FileContent file;
    try {
      file = documentFileService.getDocumentContent(id, request.getRemoteUser());

    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (Exception e) {
      LOG.warn("Error retrieving document content with id '{}'", id, e);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
    if (file == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    InputStream content;
    String mimeType;
    String name;
    if (OFFICE_MIME_TYPES.contains(file.getMimeType())) {
      try {
        content = Files.newInputStream(getOrConvertToPdf(id, file).toPath());
      } catch (DocumentPreviewException e) {
        LOG.debug("Document '{}' cannot be previewed: {}", id, e.getMessage());
        throw e;
      } catch (IOException e) {
        LOG.warn("Error converting document '{}' to PDF", id, e);
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
      }
      mimeType = "application/pdf";
      name = FilenameUtils.removeExtension(file.getName()) + ".pdf";
    } else {
      content = file.getContent();
      mimeType = StringUtils.isBlank(file.getMimeType()) ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getMimeType();
      name = file.getName();
    }
    String fileName = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                                                        .contentType(MediaType.valueOf(mimeType))
                                                        .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                                                        .header("Content-Disposition",
                                                                "filename=\"" + fileName + "\"; filename*=UTF-8''" + fileName);
    Date updatedDate = file.getUpdatedDate();
    if (updatedDate != null) {
      builder.lastModified(updatedDate.getTime()).eTag(String.valueOf(Objects.hash(updatedDate.getTime())));
    }
    return builder.body(new InputStreamResource(content));
  }

  @ExceptionHandler(DocumentPreviewException.class)
  public ResponseEntity<Map<String, Object>> handleDocumentPreviewException(DocumentPreviewException e) {
    HttpStatus status = e.getReason() == DocumentPreviewErrorReason.CONVERSION_SERVICE_UNAVAILABLE ? HttpStatus.SERVICE_UNAVAILABLE
                                                                                                     : HttpStatus.PAYLOAD_TOO_LARGE;
    return ResponseEntity.status(status).body(Map.of("reason", e.getReason().name(), "limit", e.getLimit()));
  }

  private File getOrConvertToPdf(String id, FileContent file) throws IOException {
    Date updatedDate = file.getUpdatedDate();
    String cacheKey = id + "@" + (updatedDate == null ? 0 : updatedDate.getTime());
    String cachedPath = pdfCache.get(cacheKey);
    if (cachedPath != null) {
      File cached = new File(cachedPath);
      if (cached.exists()) {
        return cached;
      }
      pdfCache.remove(cacheKey);
    }
    File converted = convertToPdf(file);
    pdfCache.put(cacheKey, converted.getAbsolutePath());
    return converted;
  }

  private File convertToPdf(FileContent file) throws IOException {
    if (!jodConverterService.isConnected()) {
      throw new DocumentPreviewException(DocumentPreviewErrorReason.CONVERSION_SERVICE_UNAVAILABLE,
                                          0,
                                          "document conversion service is currently unavailable for document '" + file.getName()
                                              + "'");
    }
    String extension;
    try {
      extension = DMSMimeTypeResolver.getInstance().getExtension(file.getMimeType());
    } catch (Exception e) {
      throw new IOException("Unable to resolve file extension for mime type '" + file.getMimeType() + "'", e);
    }
    File input = File.createTempFile("documentspreview_", "." + extension);
    File output = File.createTempFile("documentspreview_", ".pdf");
    try {
      FileUtils.copyInputStreamToFile(file.getContent(), input);
      long maxFileSizeMb = getMaxFileSizeMb();
      if (input.length() > maxFileSizeMb * 1024 * 1024) {
        FileUtils.deleteQuietly(output);
        throw new DocumentPreviewException(DocumentPreviewErrorReason.MAX_FILE_SIZE_EXCEEDED,
                                            maxFileSizeMb,
                                            "document '" + file.getName() + "' of " + input.length()
                                                + " bytes exceeds the maximum allowed size of " + maxFileSizeMb
                                                + " MB for PDF preview");
      }
      boolean converted;
      try {
        converted = jodConverterService.convert(input, output, "pdf");
      } catch (OfficeException e) {
        FileUtils.deleteQuietly(output);
        throw new IOException("Document conversion to PDF failed for document '" + file.getName() + "'", e);
      }
      if (!converted) {
        FileUtils.deleteQuietly(output);
        throw new IOException("Document conversion to PDF failed for document '" + file.getName() + "'");
      }
      long maxPages = getMaxPages();
      long pageCount = getPageCount(output);
      if (pageCount > maxPages) {
        FileUtils.deleteQuietly(output);
        throw new DocumentPreviewException(DocumentPreviewErrorReason.MAX_PAGES_EXCEEDED,
                                            maxPages,
                                            "document '" + file.getName() + "' has " + pageCount
                                                + " pages which exceeds the maximum allowed of " + maxPages
                                                + " pages for PDF preview");
      }
      return output;
    } finally {
      FileUtils.deleteQuietly(input);
    }
  }

  private long getPageCount(File pdfFile) throws IOException {
    Document document = new Document();
    try {
      document.setFile(pdfFile.getAbsolutePath());
      return document.getNumberOfPages();
    } catch (Exception e) {
      throw new IOException("Unable to read PDF page count for file '" + pdfFile.getName() + "'", e);
    } finally {
      document.dispose();
    }
  }

  private long getMaxFileSizeMb() {
    return getLongProperty(MAX_FILE_SIZE_PROPERTY_NAME, DEFAULT_MAX_FILE_SIZE_MB);
  }

  private long getMaxPages() {
    return getLongProperty(MAX_PAGES_PROPERTY_NAME, DEFAULT_MAX_PAGES);
  }

  private long getLongProperty(String name, long defaultValue) {
    String value = System.getProperty(name);
    if (StringUtils.isBlank(value)) {
      return defaultValue;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      LOG.warn("Property '{}' value '{}' is not a valid number, using default value {}", name, value, defaultValue);
      return defaultValue;
    }
  }

  enum DocumentPreviewErrorReason {
    MAX_FILE_SIZE_EXCEEDED,
    MAX_PAGES_EXCEEDED,
    CONVERSION_SERVICE_UNAVAILABLE
  }

  static final class DocumentPreviewException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final DocumentPreviewErrorReason reason;

    private final long                       limit;

    private DocumentPreviewException(DocumentPreviewErrorReason errorReason, long errorLimit, String message) {
      super(message);
      this.reason = errorReason;
      this.limit = errorLimit;
    }

    public DocumentPreviewErrorReason getReason() {
      return reason;
    }

    public long getLimit() {
      return limit;
    }

  }

}
