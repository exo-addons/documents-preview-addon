package org.exoplatform.addons.documentspreview;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.artofsolving.jodconverter.office.OfficeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.documents.service.DocumentFileService;
import org.exoplatform.services.cache.CacheService;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cms.jodconverter.JodConverterService;

import io.meeds.portal.thumbnail.model.FileContent;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link DocumentsPreviewRest}.
 */
public class DocumentsPreviewRestTest {

  private static final String MAX_FILE_SIZE_PROPERTY_NAME = "exo.documents.preview.max-file-size";

  private static final String MAX_PAGES_PROPERTY_NAME     = "exo.documents.preview.max-pages";

  private static final String DOCUMENT_ID                 = "42";

  private static final String REMOTE_USER                 = "john";

  private DocumentFileService documentFileService;

  private JodConverterService  jodConverterService;

  private HttpServletRequest   request;

  private Map<String, String>  cacheStore;

  private String                previousMaxFileSize;

  private String                previousMaxPages;

  @BeforeEach
  public void setUp() {
    previousMaxFileSize = System.getProperty(MAX_FILE_SIZE_PROPERTY_NAME);
    previousMaxPages = System.getProperty(MAX_PAGES_PROPERTY_NAME);

    documentFileService = mock(DocumentFileService.class);
    jodConverterService = mock(JodConverterService.class);
    when(jodConverterService.isConnected()).thenReturn(true);
    request = mock(HttpServletRequest.class);
    when(request.getRemoteUser()).thenReturn(REMOTE_USER);
    cacheStore = new HashMap<>();
  }

  @AfterEach
  public void tearDown() {
    restoreProperty(MAX_FILE_SIZE_PROPERTY_NAME, previousMaxFileSize);
    restoreProperty(MAX_PAGES_PROPERTY_NAME, previousMaxPages);
  }

  private void restoreProperty(String name, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, previousValue);
    }
  }

  @SuppressWarnings("unchecked")
  private DocumentsPreviewRest newRest() throws Exception {
    DocumentsPreviewRest rest = new DocumentsPreviewRest();
    setField(rest, "documentFileService", documentFileService);
    setField(rest, "jodConverterService", jodConverterService);

    CacheService cacheService = mock(CacheService.class);
    ExoCache<String, String> pdfCache = mock(ExoCache.class);
    when(pdfCache.get(any())).thenAnswer(invocation -> cacheStore.get(invocation.getArgument(0)));
    doAnswer(invocation -> cacheStore.put(invocation.getArgument(0), invocation.getArgument(1))).when(pdfCache)
                                                                                                  .put(any(), any());
    when(pdfCache.remove(any())).thenAnswer(invocation -> cacheStore.remove(invocation.getArgument(0)));
    doReturn(pdfCache).when(cacheService).getCacheInstance(anyString());
    setField(rest, "cacheService", cacheService);

    rest.init();
    return rest;
  }

  private void setField(Object target, String name, Object value) throws Exception {
    Field field = DocumentsPreviewRest.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private FileContent newFileContent(String mimeType, byte[] content, String name, Date updatedDate) {
    return new FileContent(DOCUMENT_ID, name, mimeType, new ByteArrayInputStream(content), updatedDate);
  }

  /**
   * Builds the bytes of a minimal, but structurally valid, single-xref-section PDF with the
   * given number of pages so that {@code org.icepdf.core.pobjects.Document} can genuinely parse
   * it and report a page count, without depending on any external PDF generation library.
   */
  private static byte[] buildMinimalPdf(int pageCount) throws IOException {
    StringBuilder kids = new StringBuilder();
    for (int i = 0; i < pageCount; i++) {
      if (i > 0) {
        kids.append(' ');
      }
      kids.append(3 + i).append(" 0 R");
    }
    List<byte[]> objects = new ArrayList<>();
    objects.add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
    objects.add(("2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >>\nendobj\n").getBytes(StandardCharsets.US_ASCII));
    for (int i = 0; i < pageCount; i++) {
      objects.add((3 + i
          + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> >>\nendobj\n").getBytes(StandardCharsets.US_ASCII));
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] header = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
    out.write(header);
    int[] offsets = new int[objects.size()];
    int pos = header.length;
    for (int i = 0; i < objects.size(); i++) {
      offsets[i] = pos;
      out.write(objects.get(i));
      pos += objects.get(i).length;
    }
    int xrefPos = pos;
    StringBuilder xref = new StringBuilder();
    xref.append("xref\n").append(0).append(' ').append(objects.size() + 1).append('\n');
    xref.append("0000000000 65535 f \n");
    for (int offset : offsets) {
      xref.append(String.format("%010d 00000 n \n", offset));
    }
    out.write(xref.toString().getBytes(StandardCharsets.US_ASCII));
    String trailer = "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xrefPos + "\n%%EOF";
    out.write(trailer.getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
  }

  private void mockConversionTo(byte[] pdfBytes) throws OfficeException {
    when(jodConverterService.convert(any(File.class), any(File.class), eq("pdf"))).thenAnswer(invocation -> {
      File output = invocation.getArgument(1);
      try (FileOutputStream fos = new FileOutputStream(output)) {
        fos.write(pdfBytes);
      }
      return true;
    });
  }

  private byte[] readAll(InputStream inputStream) throws IOException {
    return inputStream.readAllBytes();
  }

  @Test
  public void getDocumentContentShouldReturn400WhenIdIsBlank() throws Exception {
    DocumentsPreviewRest rest = newRest();

    ResponseStatusException exception =
                                       assertThrows(ResponseStatusException.class, () -> rest.getDocumentContent(request, " "));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertEquals("document_id_is_mandatory", exception.getReason());
  }

  @Test
  public void getDocumentContentShouldReturn400WhenIdIsNull() throws Exception {
    DocumentsPreviewRest rest = newRest();

    ResponseStatusException exception =
                                       assertThrows(ResponseStatusException.class, () -> rest.getDocumentContent(request, null));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
  }

  @Test
  public void getDocumentContentShouldReturn404WhenDocumentNotFound() throws Exception {
    DocumentsPreviewRest rest = newRest();
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenThrow(new ObjectNotFoundException("document not found"));

    ResponseStatusException exception =
                                       assertThrows(ResponseStatusException.class,
                                                     () -> rest.getDocumentContent(request, DOCUMENT_ID));

    assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    assertEquals("document not found", exception.getReason());
  }

  @Test
  public void getDocumentContentShouldReturn500WhenServiceThrowsUnexpectedException() throws Exception {
    DocumentsPreviewRest rest = newRest();
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenThrow(new RuntimeException("boom"));

    ResponseStatusException exception =
                                       assertThrows(ResponseStatusException.class,
                                                     () -> rest.getDocumentContent(request, DOCUMENT_ID));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
  }

  @Test
  public void getDocumentContentShouldReturn404WhenFileContentIsNull() throws Exception {
    DocumentsPreviewRest rest = newRest();
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenReturn(null);

    ResponseStatusException exception =
                                       assertThrows(ResponseStatusException.class,
                                                     () -> rest.getDocumentContent(request, DOCUMENT_ID));

    assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
  }

  @Test
  public void getDocumentContentShouldReturnFileDirectlyForNonOfficeMimeType() throws Exception {
    DocumentsPreviewRest rest = newRest();
    byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
    Date updatedDate = new Date(1_700_000_000_000L);
    FileContent fileContent = newFileContent("text/plain", content, "notes.txt", updatedDate);
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenReturn(fileContent);

    ResponseEntity<?> response = rest.getDocumentContent(request, DOCUMENT_ID);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType());
    assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("notes.txt"));
    assertTrue(response.getHeaders().getCacheControl().contains("max-age=604800"));
    assertTrue(response.getHeaders().getCacheControl().contains("private"));
    assertEquals(updatedDate.getTime() / 1000 * 1000, response.getHeaders().getLastModified());
    assertNotNull(response.getHeaders().getETag());
    verify(jodConverterService, never()).convert(any(), any(), anyString());
  }

  @Test
  public void getDocumentContentShouldDefaultToOctetStreamWhenMimeTypeIsBlank() throws Exception {
    DocumentsPreviewRest rest = newRest();
    byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
    FileContent fileContent = newFileContent("", content, "unknown", null);
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenReturn(fileContent);

    ResponseEntity<?> response = rest.getDocumentContent(request, DOCUMENT_ID);

    assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
    assertEquals(-1, response.getHeaders().getLastModified());
  }

  @Test
  public void getDocumentContentShouldConvertOfficeDocumentAndReturnPdf() throws Exception {
    DocumentsPreviewRest rest = newRest();
    byte[] pdfBytes = buildMinimalPdf(1);
    mockConversionTo(pdfBytes);
    Date updatedDate = new Date(1_700_000_000_000L);
    FileContent fileContent = newFileContent("application/msword", "not really a word doc".getBytes(StandardCharsets.UTF_8),
                                              "report.doc", updatedDate);
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenReturn(fileContent);

    ResponseEntity<InputStreamResource> response = rest.getDocumentContent(request, DOCUMENT_ID);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
    assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("report.pdf"));
    assertArrayEquals(pdfBytes, readAll(response.getBody().getInputStream()));
    verify(jodConverterService, times(1)).convert(any(File.class), any(File.class), eq("pdf"));
  }

  @Test
  public void getDocumentContentShouldReuseCachedPdfOnSecondCall() throws Exception {
    DocumentsPreviewRest rest = newRest();
    byte[] pdfBytes = buildMinimalPdf(1);
    mockConversionTo(pdfBytes);
    FileContent fileContent = newFileContent("application/msword", "doc-bytes".getBytes(StandardCharsets.UTF_8),
                                              "report.doc", new Date(1_700_000_000_000L));
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenReturn(fileContent);

    rest.getDocumentContent(request, DOCUMENT_ID);
    rest.getDocumentContent(request, DOCUMENT_ID);

    verify(jodConverterService, times(1)).convert(any(File.class), any(File.class), eq("pdf"));
  }

  @Test
  public void getDocumentContentShouldThrowLimitExceededWhenInputFileExceedsMaxFileSize() throws Exception {
    System.setProperty(MAX_FILE_SIZE_PROPERTY_NAME, "0");
    DocumentsPreviewRest rest = newRest();
    FileContent fileContent = newFileContent("application/msword", "some content".getBytes(StandardCharsets.UTF_8),
                                              "report.doc", null);
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenReturn(fileContent);

    DocumentsPreviewRest.DocumentPreviewException exception =
                                                             assertThrows(DocumentsPreviewRest.DocumentPreviewException.class,
                                                                          () -> rest.getDocumentContent(request, DOCUMENT_ID));

    assertEquals(DocumentsPreviewRest.DocumentPreviewErrorReason.MAX_FILE_SIZE_EXCEEDED, exception.getReason());
    assertEquals(0L, exception.getLimit());
    verify(jodConverterService, never()).convert(any(), any(), anyString());

    ResponseEntity<Map<String, Object>> response = rest.handleDocumentPreviewException(exception);

    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
    assertEquals("MAX_FILE_SIZE_EXCEEDED", response.getBody().get("reason"));
    assertEquals(0L, response.getBody().get("limit"));
  }

  @Test
  public void getDocumentContentShouldThrowLimitExceededWhenConvertedDocumentExceedsMaxPages() throws Exception {
    System.setProperty(MAX_PAGES_PROPERTY_NAME, "1");
    DocumentsPreviewRest rest = newRest();
    mockConversionTo(buildMinimalPdf(2));
    FileContent fileContent = newFileContent("application/msword", "doc-bytes".getBytes(StandardCharsets.UTF_8),
                                              "report.doc", null);
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenReturn(fileContent);

    DocumentsPreviewRest.DocumentPreviewException exception =
                                                             assertThrows(DocumentsPreviewRest.DocumentPreviewException.class,
                                                                          () -> rest.getDocumentContent(request, DOCUMENT_ID));

    assertEquals(DocumentsPreviewRest.DocumentPreviewErrorReason.MAX_PAGES_EXCEEDED, exception.getReason());
    assertEquals(1L, exception.getLimit());

    ResponseEntity<Map<String, Object>> response = rest.handleDocumentPreviewException(exception);

    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
    assertEquals("MAX_PAGES_EXCEEDED", response.getBody().get("reason"));
    assertEquals(1L, response.getBody().get("limit"));
  }

  @Test
  public void getDocumentContentShouldThrowServiceUnavailableWhenConversionServiceIsDisconnected() throws Exception {
    DocumentsPreviewRest rest = newRest();
    when(jodConverterService.isConnected()).thenReturn(false);
    FileContent fileContent = newFileContent("application/msword", "doc-bytes".getBytes(StandardCharsets.UTF_8),
                                              "report.doc", null);
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenReturn(fileContent);

    DocumentsPreviewRest.DocumentPreviewException exception =
                                                             assertThrows(DocumentsPreviewRest.DocumentPreviewException.class,
                                                                          () -> rest.getDocumentContent(request, DOCUMENT_ID));

    assertEquals(DocumentsPreviewRest.DocumentPreviewErrorReason.CONVERSION_SERVICE_UNAVAILABLE, exception.getReason());
    assertEquals(0L, exception.getLimit());
    verify(jodConverterService, never()).convert(any(), any(), anyString());

    ResponseEntity<Map<String, Object>> response = rest.handleDocumentPreviewException(exception);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertEquals("CONVERSION_SERVICE_UNAVAILABLE", response.getBody().get("reason"));
    assertEquals(0L, response.getBody().get("limit"));
  }

  @Test
  public void getDocumentContentShouldReturn500WhenConversionFails() throws Exception {
    DocumentsPreviewRest rest = newRest();
    when(jodConverterService.convert(any(File.class), any(File.class), eq("pdf"))).thenReturn(false);
    FileContent fileContent = newFileContent("application/msword", "doc-bytes".getBytes(StandardCharsets.UTF_8),
                                              "report.doc", null);
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenReturn(fileContent);

    ResponseStatusException exception =
                                       assertThrows(ResponseStatusException.class,
                                                     () -> rest.getDocumentContent(request, DOCUMENT_ID));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
  }

  @Test
  public void getDocumentContentShouldReturn500WhenConversionThrowsOfficeException() throws Exception {
    DocumentsPreviewRest rest = newRest();
    when(jodConverterService.convert(any(File.class), any(File.class), eq("pdf"))).thenThrow(new OfficeException("boom"));
    FileContent fileContent = newFileContent("application/msword", "doc-bytes".getBytes(StandardCharsets.UTF_8),
                                              "report.doc", null);
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenReturn(fileContent);

    ResponseStatusException exception =
                                       assertThrows(ResponseStatusException.class,
                                                     () -> rest.getDocumentContent(request, DOCUMENT_ID));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
  }

  @Test
  public void getDocumentContentShouldReturn500WhenConvertedFileIsNotAValidPdf() throws Exception {
    DocumentsPreviewRest rest = newRest();
    when(jodConverterService.convert(any(File.class), any(File.class), eq("pdf"))).thenAnswer(invocation -> {
      File output = invocation.getArgument(1);
      try (FileOutputStream fos = new FileOutputStream(output)) {
        fos.write("not a real pdf".getBytes(StandardCharsets.UTF_8));
      }
      return true;
    });
    FileContent fileContent = newFileContent("application/msword", "doc-bytes".getBytes(StandardCharsets.UTF_8),
                                              "report.doc", null);
    when(documentFileService.getDocumentContent(DOCUMENT_ID, REMOTE_USER)).thenReturn(fileContent);

    ResponseStatusException exception =
                                       assertThrows(ResponseStatusException.class,
                                                     () -> rest.getDocumentContent(request, DOCUMENT_ID));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
  }

}
