package org.exoplatform.services.cms.jodconverter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;

import org.artofsolving.jodconverter.OfficeDocumentConverter;
import org.artofsolving.jodconverter.document.DocumentFormat;
import org.artofsolving.jodconverter.document.DocumentFormatRegistry;
import org.artofsolving.jodconverter.office.OfficeException;
import org.artofsolving.jodconverter.office.OfficeManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValueParam;

/**
 * Unit tests for {@link JodConverterServiceImpl}.
 */
public class JodConverterServiceImplTest {

  private static final String ENABLE_PROPERTY      = "exo.jodconverter.enable";

  // JODConverter's OfficeUtils.getDefaultOfficeHome() reads this system property first, before
  // falling back to platform-specific auto-detection (e.g. a real LibreOffice install that may or
  // may not be present on the machine running the tests). Pinning it makes the officeHome
  // resolution deterministic regardless of what is actually installed on the test host.
  private static final String OFFICE_HOME_PROPERTY = "office.home";

  private String               previousEnableValue;

  private String               previousOfficeHomeValue;

  @BeforeEach
  public void backupSystemProperties() {
    previousEnableValue = System.getProperty(ENABLE_PROPERTY);
    previousOfficeHomeValue = System.getProperty(OFFICE_HOME_PROPERTY);
  }

  @AfterEach
  public void restoreSystemProperties() {
    if (previousEnableValue == null) {
      System.clearProperty(ENABLE_PROPERTY);
    } else {
      System.setProperty(ENABLE_PROPERTY, previousEnableValue);
    }
    if (previousOfficeHomeValue == null) {
      System.clearProperty(OFFICE_HOME_PROPERTY);
    } else {
      System.setProperty(OFFICE_HOME_PROPERTY, previousOfficeHomeValue);
    }
  }

  /**
   * Creates a fake office installation directory containing a placeholder
   * "program/soffice.bin" file, which is all JODConverter's
   * {@code DefaultOfficeManagerConfiguration.buildOfficeManager()} checks for: it never
   * validates that the file is a real, executable LibreOffice binary.
   */
  private File newFakeOfficeHome(File parent) throws Exception {
    File programDir = new File(parent, "program");
    programDir.mkdirs();
    File sofficeBin = new File(programDir, "soffice.bin");
    sofficeBin.createNewFile();
    return parent;
  }

  private InitParams newInitParams(String officeHome,
                                    String port,
                                    String taskQueueTimeout,
                                    String taskExecutionTimeout,
                                    String maxTasksPerProcess,
                                    String retryTimeout) {
    InitParams initParams = new InitParams();
    initParams.addParameter(valueParam("officeHome", officeHome));
    initParams.addParameter(valueParam("port", port));
    initParams.addParameter(valueParam("taskQueueTimeout", taskQueueTimeout));
    initParams.addParameter(valueParam("taskExecutionTimeout", taskExecutionTimeout));
    initParams.addParameter(valueParam("maxTasksPerProcess", maxTasksPerProcess));
    initParams.addParameter(valueParam("retryTimeout", retryTimeout));
    return initParams;
  }

  private ValueParam valueParam(String name, String value) {
    ValueParam valueParam = new ValueParam();
    valueParam.setName(name);
    valueParam.setValue(value);
    return valueParam;
  }

  private Object getField(Object target, String name) throws Exception {
    Field field = JodConverterServiceImpl.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private void setField(Object target, String name, Object value) throws Exception {
    Field field = JodConverterServiceImpl.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private JodConverterServiceImpl newDisabledService() throws Exception {
    System.setProperty(ENABLE_PROPERTY, "false");
    return new JodConverterServiceImpl(new InitParams());
  }

  @Test
  public void constructorShouldDisableServiceWhenSystemPropertyIsFalse() throws Exception {
    JodConverterServiceImpl service = newDisabledService();

    assertEquals(Boolean.FALSE, getField(service, "enable"));
    assertNull(getField(service, "officeManager"));
    assertNull(getField(service, "documentConverter"));
  }

  @Test
  public void constructorShouldEnableServiceByDefaultWhenSystemPropertyIsNotSet() throws Exception {
    System.clearProperty(ENABLE_PROPERTY);

    JodConverterServiceImpl service = new JodConverterServiceImpl(newInitParams("", null, null, null, null, null));

    assertEquals(Boolean.TRUE, getField(service, "enable"));
  }

  @Test
  public void constructorShouldEnableServiceWhenSystemPropertyIsTrue() throws Exception {
    System.setProperty(ENABLE_PROPERTY, "true");

    JodConverterServiceImpl service = new JodConverterServiceImpl(newInitParams("", null, null, null, null, null));

    assertEquals(Boolean.TRUE, getField(service, "enable"));
  }

  @Test
  public void constructorShouldLeaveOfficeManagerNullWhenOfficeHomeIsBlank(@TempDir File tempDir) throws Exception {
    System.setProperty(ENABLE_PROPERTY, "true");
    // Pin the auto-detection fallback to a directory with no "program/soffice.bin" in it, so the
    // outcome does not depend on whether a real office suite happens to be installed on this host.
    System.setProperty(OFFICE_HOME_PROPERTY, tempDir.getAbsolutePath());

    JodConverterServiceImpl service = new JodConverterServiceImpl(newInitParams("", null, null, null, null, null));

    assertNull(getField(service, "officeManager"));
    assertNull(getField(service, "documentConverter"));
    assertFalse(service.convert(new File("input.doc"), new File("output.pdf"), "pdf"));
  }

  @Test
  public void constructorShouldLeaveOfficeManagerNullWhenOfficeHomeDoesNotExist(@TempDir File tempDir) throws Exception {
    System.setProperty(ENABLE_PROPERTY, "true");
    System.setProperty(OFFICE_HOME_PROPERTY, tempDir.getAbsolutePath());

    JodConverterServiceImpl service =
                                     new JodConverterServiceImpl(newInitParams("/no/such/office/directory",
                                                                                null,
                                                                                null,
                                                                                null,
                                                                                null,
                                                                                null));

    assertNull(getField(service, "officeManager"));
  }

  @Test
  public void constructorShouldBuildOfficeManagerAndRegisterJpegFormatWhenOfficeHomeIsValid(@TempDir File tempDir) throws Exception {
    System.setProperty(ENABLE_PROPERTY, "true");
    File officeHome = newFakeOfficeHome(tempDir);

    JodConverterServiceImpl service =
                                     new JodConverterServiceImpl(newInitParams(officeHome.getAbsolutePath(),
                                                                                "8100",
                                                                                "10000",
                                                                                "20000",
                                                                                "50",
                                                                                "5000"));

    Object officeManager = getField(service, "officeManager");
    Object documentConverter = getField(service, "documentConverter");
    assertNotNull(officeManager);
    assertTrue(officeManager instanceof OfficeManager);
    assertNotNull(documentConverter);

    DocumentFormatRegistry registry = ((OfficeDocumentConverter) documentConverter).getFormatRegistry();
    DocumentFormat jpegFormat = registry.getFormatByExtension("jpg");
    assertNotNull(jpegFormat);
    assertEquals("image/jpeg", jpegFormat.getMediaType());
  }

  @Test
  public void constructorShouldIgnoreInvalidPortNumbersAndStillBuildOfficeManager(@TempDir File tempDir) throws Exception {
    System.setProperty(ENABLE_PROPERTY, "true");
    File officeHome = newFakeOfficeHome(tempDir);

    JodConverterServiceImpl service =
                                     new JodConverterServiceImpl(newInitParams(officeHome.getAbsolutePath(),
                                                                                "not-a-port,also-invalid",
                                                                                null,
                                                                                null,
                                                                                null,
                                                                                null));

    assertNotNull(getField(service, "officeManager"));
  }

  @Test
  public void constructorShouldIgnoreInvalidNumericParametersAndStillBuildOfficeManager(@TempDir File tempDir) throws Exception {
    System.setProperty(ENABLE_PROPERTY, "true");
    File officeHome = newFakeOfficeHome(tempDir);

    JodConverterServiceImpl service =
                                     new JodConverterServiceImpl(newInitParams(officeHome.getAbsolutePath(),
                                                                                null,
                                                                                "not-a-long",
                                                                                "not-a-long",
                                                                                "not-an-int",
                                                                                "not-a-long"));

    assertNotNull(getField(service, "officeManager"));
  }

  @Test
  public void convertShouldReturnFalseWhenServiceIsDisabled() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "officeManager", mock(OfficeManager.class));
    setField(service, "documentConverter", mock(OfficeDocumentConverter.class));

    boolean converted = service.convert(new File("input.doc"), new File("output.pdf"), "pdf");

    assertFalse(converted);
  }

  @Test
  public void convertShouldReturnFalseWhenOfficeManagerIsNotRunning() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);
    OfficeManager officeManager = mock(OfficeManager.class);
    when(officeManager.isRunning()).thenReturn(false);
    setField(service, "officeManager", officeManager);

    boolean converted = service.convert(new File("input.doc"), new File("output.pdf"), "pdf");

    assertFalse(converted);
  }

  @Test
  public void convertShouldReturnFalseWhenOutputFormatIsUnsupported() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);
    OfficeManager officeManager = mock(OfficeManager.class);
    when(officeManager.isRunning()).thenReturn(true);
    setField(service, "officeManager", officeManager);
    OfficeDocumentConverter documentConverter = mock(OfficeDocumentConverter.class);
    DocumentFormatRegistry registry = mock(DocumentFormatRegistry.class);
    when(documentConverter.getFormatRegistry()).thenReturn(registry);
    when(registry.getFormatByExtension("unknown")).thenReturn(null);
    setField(service, "documentConverter", documentConverter);

    boolean converted = service.convert(new File("input.doc"), new File("output.unknown"), "unknown");

    assertFalse(converted);
  }

  @Test
  public void convertShouldReturnTrueWhenConversionSucceeds() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);
    OfficeManager officeManager = mock(OfficeManager.class);
    when(officeManager.isRunning()).thenReturn(true);
    setField(service, "officeManager", officeManager);
    OfficeDocumentConverter documentConverter = mock(OfficeDocumentConverter.class);
    DocumentFormatRegistry registry = mock(DocumentFormatRegistry.class);
    DocumentFormat pdfFormat = new DocumentFormat("Portable Document Format", "pdf", "application/pdf");
    when(documentConverter.getFormatRegistry()).thenReturn(registry);
    when(registry.getFormatByExtension("pdf")).thenReturn(pdfFormat);
    setField(service, "documentConverter", documentConverter);
    File input = new File("input.doc");
    File output = new File("output.pdf");

    boolean converted = service.convert(input, output, "pdf");

    assertTrue(converted);
    verify(documentConverter, times(1)).convert(eq(input), eq(output), eq(pdfFormat));
  }

  @Test
  public void convertShouldReturnFalseWhenConversionThrows() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);
    OfficeManager officeManager = mock(OfficeManager.class);
    when(officeManager.isRunning()).thenReturn(true);
    setField(service, "officeManager", officeManager);
    OfficeDocumentConverter documentConverter = mock(OfficeDocumentConverter.class);
    DocumentFormatRegistry registry = mock(DocumentFormatRegistry.class);
    DocumentFormat pdfFormat = new DocumentFormat("Portable Document Format", "pdf", "application/pdf");
    when(documentConverter.getFormatRegistry()).thenReturn(registry);
    when(registry.getFormatByExtension("pdf")).thenReturn(pdfFormat);
    org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                        .when(documentConverter)
                        .convert(any(File.class), any(File.class), any(DocumentFormat.class));
    setField(service, "documentConverter", documentConverter);

    boolean converted = service.convert(new File("input.doc"), new File("output.pdf"), "pdf");

    assertFalse(converted);
  }

  @Test
  public void startShouldNotTouchOfficeManagerWhenServiceIsDisabled() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    OfficeManager officeManager = mock(OfficeManager.class);
    setField(service, "officeManager", officeManager);

    service.start();

    verify(officeManager, never()).start();
  }

  @Test
  public void startShouldDoNothingWhenEnabledAndOfficeManagerIsNull() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);

    service.start();
  }

  @Test
  public void startShouldStartOfficeManagerWhenEnabled() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);
    OfficeManager officeManager = mock(OfficeManager.class);
    setField(service, "officeManager", officeManager);

    service.start();

    verify(officeManager, times(1)).start();
  }

  @Test
  public void startShouldSwallowOfficeExceptionWhenOfficeManagerFailsToStart() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);
    OfficeManager officeManager = mock(OfficeManager.class);
    org.mockito.Mockito.doThrow(new OfficeException("boom")).when(officeManager).start();
    setField(service, "officeManager", officeManager);

    service.start();

    verify(officeManager, times(1)).start();
  }

  @Test
  public void stopShouldNotTouchOfficeManagerWhenServiceIsDisabled() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    OfficeManager officeManager = mock(OfficeManager.class);
    setField(service, "officeManager", officeManager);

    service.stop();

    verify(officeManager, never()).stop();
  }

  @Test
  public void stopShouldStopOfficeManagerWhenEnabled() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);
    OfficeManager officeManager = mock(OfficeManager.class);
    setField(service, "officeManager", officeManager);

    service.stop();

    verify(officeManager, times(1)).stop();
  }

  @Test
  public void stopShouldSwallowOfficeExceptionWhenOfficeManagerFailsToStop() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);
    OfficeManager officeManager = mock(OfficeManager.class);
    org.mockito.Mockito.doThrow(new OfficeException("boom")).when(officeManager).stop();
    setField(service, "officeManager", officeManager);

    service.stop();

    verify(officeManager, times(1)).stop();
  }

  @Test
  public void isConnectedShouldReturnFalseWhenServiceIsDisabled() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    OfficeManager officeManager = mock(OfficeManager.class);
    when(officeManager.isRunning()).thenReturn(true);
    setField(service, "officeManager", officeManager);

    assertFalse(service.isConnected());
  }

  @Test
  public void isConnectedShouldReturnFalseWhenOfficeManagerIsNull() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);

    assertFalse(service.isConnected());
  }

  @Test
  public void isConnectedShouldReturnFalseWhenOfficeManagerIsNotRunning() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);
    OfficeManager officeManager = mock(OfficeManager.class);
    when(officeManager.isRunning()).thenReturn(false);
    setField(service, "officeManager", officeManager);

    assertFalse(service.isConnected());
  }

  @Test
  public void isConnectedShouldReturnTrueWhenEnabledAndOfficeManagerIsRunning() throws Exception {
    JodConverterServiceImpl service = newDisabledService();
    setField(service, "enable", Boolean.TRUE);
    OfficeManager officeManager = mock(OfficeManager.class);
    when(officeManager.isRunning()).thenReturn(true);
    setField(service, "officeManager", officeManager);

    assertTrue(service.isConnected());
  }

}
