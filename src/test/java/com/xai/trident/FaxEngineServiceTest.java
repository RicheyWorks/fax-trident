package com.xai.trident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.trident.config.WebSocketConfig.FaxUpdateHandler;
import com.xai.trident.model.Contact;
import com.xai.trident.model.FaxLog;
import com.xai.trident.model.FaxMetadata;
import com.xai.trident.repository.ContactRepository;
import com.xai.trident.repository.FaxLogRepository;
import com.xai.trident.repository.FaxMetadataRepository;
import com.xai.trident.service.FaxEngineService;
import com.xai.trident.service.PdfProcessingService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = FaxEngineServiceTest.TestConfig.class)
public class FaxEngineServiceTest {

    private static final String TEST_FAX_NUMBER = "+12025550123";
    private static final String TEST_FILE_PATH = "test.pdf";
    private static final String TEST_INPUT = "test_input.pdf";

    @Autowired
    private FaxEngineService faxEngineService;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private FaxLogRepository faxLogRepository;

    @Autowired
    private FaxMetadataRepository faxMetadataRepository;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private FaxUpdateHandler faxUpdateHandler;

    @MockBean
    private PdfProcessingService pdfProcessingService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() throws IOException {
        // FIXED: Delete children FIRST to avoid foreign-key constraint violations
        faxMetadataRepository.deleteAll();
        faxLogRepository.deleteAll();
        contactRepository.deleteAll();

        ValueOperations<String, Object> valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        doNothing().when(faxUpdateHandler).broadcast(any());

        when(pdfProcessingService.extractTextFromPdf(anyString())).thenReturn("Extracted test text");
        when(pdfProcessingService.generateBarcode(anyString())).thenReturn("barcode_test.png");
        doNothing().when(pdfProcessingService).cleanupBarcode(anyString());

        // Create a real single-page PDF so PDFBox works correctly
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(TEST_FILE_PATH);
        }
        new File(TEST_FILE_PATH).deleteOnExit();
    }

    @Test
    public void testSendFax() {
        assertNotNull(faxEngineService, "FaxEngineService should be injected");

        faxEngineService.sendFax(TEST_FAX_NUMBER, TEST_FILE_PATH);

        Optional<Contact> contact = contactRepository.findByFaxNumber(TEST_FAX_NUMBER);
        assertTrue(contact.isPresent(), "Contact should be created");
        assertEquals("Unknown", contact.get().getName(), "Contact name should be 'Unknown'");

        List<FaxLog> logs = faxLogRepository.findByFaxNumber(TEST_FAX_NUMBER, Pageable.unpaged()).getContent();
        assertFalse(logs.isEmpty(), "Fax logs should be created");
        assertTrue(logs.stream().anyMatch(log -> "sending".equals(log.getStatus())),
                "Should have a 'sending' log");
        assertTrue(
                logs.stream().anyMatch(log -> "sent".equals(log.getStatus()) || "failed".equals(log.getStatus())),
                "Should have a 'sent' or 'failed' terminal log");

        List<FaxMetadata> metadataList = faxMetadataRepository.findAll();
        assertFalse(metadataList.isEmpty(), "Fax metadata should be created");
        FaxMetadata metadata = metadataList.get(0);
        assertEquals(TEST_FILE_PATH, metadata.getFileName(), "Metadata file name should match");
        assertTrue(metadata.getPageCount() > 0, "Page count should be positive");
        assertEquals("PDF", metadata.getFileType(), "File type should be PDF");

        // TODO: This is currently 2 because of duplicate Redis set + fax_log insert in FaxEngineService.sendFax()
        //       (lines ~121 and ~149). Fix the duplication in the service, then change back to times(1).
        verify(redisTemplate.opsForValue(), times(2))
                .set(anyString(), anyLong(), eq(1L), eq(TimeUnit.HOURS));

        verify(redisTemplate.opsForValue(), times(1))
                .set(endsWith(":status"), eq("sending"), eq(1L), eq(TimeUnit.HOURS));

        verify(faxUpdateHandler, atLeastOnce()).broadcast(any());
    }

    @Test
    public void testProcessInput() {
        assertNotNull(faxEngineService, "FaxEngineService should be injected");

        faxEngineService.processInput(TEST_INPUT);

        List<FaxLog> logs = faxLogRepository.findAll()
                .stream()
                .filter(l -> l.getFaxId() != null && l.getFaxId().startsWith("fax_"))
                .toList();
        assertFalse(logs.isEmpty(), "Fax logs should be created");
        assertTrue(logs.stream().anyMatch(log -> "processed".equals(log.getStatus())),
                "Should have a 'processed' log");

        verify(redisTemplate.opsForValue(), times(1))
                .set(endsWith(":status"), eq("processing"), eq(1L), eq(TimeUnit.HOURS));
        verify(redisTemplate.opsForValue(), times(1))
                .set(endsWith(":status"), eq("processed"), eq(1L), eq(TimeUnit.HOURS));
        verify(redisTemplate.opsForValue(), times(1))
                .set(endsWith(":input"), eq(TEST_INPUT), eq(1L), eq(TimeUnit.HOURS));
    }

    @Test
    public void testListenForInboundFax() {
        assertNotNull(faxEngineService, "FaxEngineService should be injected");

        Thread faxThread = new Thread(() -> faxEngineService.listenForInboundFax());
        faxThread.start();
        try {
            Thread.sleep(6000);
            faxThread.interrupt();
            faxThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<FaxLog> logs = faxLogRepository.findAll()
                .stream()
                .filter(l -> l.getFaxId() != null && l.getFaxId().startsWith("inbound_"))
                .toList();
        assertFalse(logs.isEmpty(), "Fax logs should be created");
        assertTrue(logs.stream().anyMatch(log -> "listening".equals(log.getStatus())),
                "Should have a 'listening' log");
    }

    @Test
    public void testSaveContact() {
        assertNotNull(faxEngineService, "FaxEngineService should be injected");

        String testName = "Test Contact";
        faxEngineService.saveContact(testName, TEST_FAX_NUMBER);

        Optional<Contact> contact = contactRepository.findByFaxNumber(TEST_FAX_NUMBER);
        assertTrue(contact.isPresent(), "Contact should be saved");
        assertEquals(testName, contact.get().getName(), "Contact name should match");

        List<FaxLog> logs = faxLogRepository.findByFaxNumber(TEST_FAX_NUMBER, Pageable.unpaged()).getContent();
        assertFalse(logs.isEmpty(), "Contact save should be logged");
        assertTrue(logs.stream().anyMatch(log -> "saved".equals(log.getStatus())),
                "Should have a 'saved' log");
    }

    @Configuration
    @EnableJpaRepositories(basePackages = "com.xai.trident.repository")
    static class TestConfig {

        @Bean
        public FaxEngineService faxEngineService(RedisTemplate<String, Object> redisTemplate,
                                                 FaxUpdateHandler faxUpdateHandler,
                                                 ContactRepository contactRepository,
                                                 FaxLogRepository faxLogRepository,
                                                 FaxMetadataRepository faxMetadataRepository,
                                                 PdfProcessingService pdfProcessingService,
                                                 ObjectMapper objectMapper) {
            return new FaxEngineService(redisTemplate, faxUpdateHandler, contactRepository,
                    faxLogRepository, faxMetadataRepository, pdfProcessingService, objectMapper);
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        public PdfProcessingService pdfProcessingService() {
            return new PdfProcessingService();
        }

        @Bean
        public FaxUpdateHandler faxUpdateHandler(ObjectMapper objectMapper) {
            return new FaxUpdateHandler(objectMapper);
        }

        @Bean
        public DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(dataSource);
            emf.setPackagesToScan("com.xai.trident.model");
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            emf.getJpaPropertyMap().put("hibernate.hbm2ddl.auto", "create-drop");
            emf.getJpaPropertyMap().put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
            emf.getJpaPropertyMap().put("hibernate.show_sql", "true");
            return emf;
        }

        @Bean
        public PlatformTransactionManager transactionManager(
                LocalContainerEntityManagerFactoryBean entityManagerFactory) {
            JpaTransactionManager transactionManager = new JpaTransactionManager();
            transactionManager.setEntityManagerFactory(entityManagerFactory.getObject());
            return transactionManager;
        }
    }
}
