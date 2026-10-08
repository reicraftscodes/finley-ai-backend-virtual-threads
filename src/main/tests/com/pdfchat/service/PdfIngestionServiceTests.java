package com.pdfchat.service;

import com.pdfchat.entity.DocumentEntity;
import com.pdfchat.model.DocumentStatus;
import com.pdfchat.model.UploadResponse;
import com.pdfchat.repository.DocumentRepository;
import com.pdfchat.service.impl.PdfIngestionServiceImpl;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static com.pdfchat.constants.DocumentIngestionConstant.MAX_PDF_LIMIT;
import static com.pdfchat.constants.DocumentIngestionConstant.UPLOAD_DIR;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PdfIngestionServiceTests {

    // the service saves a copy of each PDF in UPLOAD_DIR, these names are cleaned up after every test
    private static final String TEST_FILE = "unit-test-policy.pdf";
    private static final String TEST_FILE_WITH_SPACES = "unit-test policy (1).pdf";
    private static final String TEST_FILE_SANITISED = "unit-test_policy__1_.pdf";

    @Mock
    private VectorStore vectorStore;

    @Mock
    private CloudinaryService cloudinaryService;

    @Mock
    private DocumentRepository documentRepository;

    @InjectMocks
    private PdfIngestionServiceImpl pdfIngestionService;

    @AfterEach
    public void deleteSavedTestFiles() throws IOException {
        Files.deleteIfExists(Paths.get(UPLOAD_DIR, TEST_FILE));
        Files.deleteIfExists(Paths.get(UPLOAD_DIR, TEST_FILE_SANITISED));
    }

    @Test
    public void ingestPdfWhenUploadLimitReached() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", TEST_FILE, "application/pdf", createPdf("Some text"));
        when(documentRepository.count()).thenReturn((long) MAX_PDF_LIMIT);

        IllegalArgumentException thrown = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            pdfIngestionService.ingestPdf(file);
        });

        Assertions.assertEquals("Upload limit reached", thrown.getMessage());
        verify(cloudinaryService, never()).uploadPdf(any());
    }

    @Test
    public void validPdfReturnsSuccessResponse() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", TEST_FILE, "application/pdf",
                createPdf("This is a sample information security policy for the company."));
        when(documentRepository.count()).thenReturn(0L);
        when(cloudinaryService.uploadPdf(file)).thenReturn("public-id");
        when(cloudinaryService.getPdfUrl("public-id")).thenReturn("https://cloudinary.test/public-id.pdf");

        UploadResponse response = pdfIngestionService.ingestPdf(file);

        Assertions.assertEquals("Success", response.getStatus());
        Assertions.assertEquals(TEST_FILE, response.getFile());
        Assertions.assertTrue(response.getIndexedChunks() > 0);
        verify(vectorStore).add(any());
    }

    @Test
    public void validPdfRecordEndsAsReady() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", TEST_FILE, "application/pdf",
                createPdf("This is a sample information security policy for the company."));
        when(documentRepository.count()).thenReturn(0L);
        when(cloudinaryService.uploadPdf(file)).thenReturn("public-id");
        when(cloudinaryService.getPdfUrl("public-id")).thenReturn("https://cloudinary.test/public-id.pdf");

        pdfIngestionService.ingestPdf(file);

        // the same record is saved twice, first as PROCESSING and then as READY
        ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentRepository, times(2)).save(captor.capture());
        DocumentEntity savedRecord = captor.getValue();
        Assertions.assertEquals(TEST_FILE, savedRecord.getFilename());
        Assertions.assertEquals("public-id", savedRecord.getCloudinaryPublicId());
        Assertions.assertEquals("https://cloudinary.test/public-id.pdf", savedRecord.getCloudinaryUrl());
        Assertions.assertEquals(DocumentStatus.READY, savedRecord.getStatus());
    }

    @Test
    public void filenameWithSpecialCharactersIsSanitised() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", TEST_FILE_WITH_SPACES, "application/pdf",
                createPdf("This is a sample information security policy for the company."));
        when(documentRepository.count()).thenReturn(0L);
        when(cloudinaryService.uploadPdf(file)).thenReturn("public-id");
        when(cloudinaryService.getPdfUrl("public-id")).thenReturn("https://cloudinary.test/public-id.pdf");

        UploadResponse response = pdfIngestionService.ingestPdf(file);

        Assertions.assertEquals(TEST_FILE_SANITISED, response.getFile());
    }

    @Test
    public void existingVectorsAreClearedBeforeIngesting() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", TEST_FILE, "application/pdf",
                createPdf("This is a sample information security policy for the company."));
        Document oldDocument = new Document("Old document text");
        when(documentRepository.count()).thenReturn(0L);
        when(cloudinaryService.uploadPdf(file)).thenReturn("public-id");
        when(cloudinaryService.getPdfUrl("public-id")).thenReturn("https://cloudinary.test/public-id.pdf");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(oldDocument));

        pdfIngestionService.ingestPdf(file);

        verify(vectorStore).delete(List.of(oldDocument.getId()));
    }

    @Test
    public void pdfWithNoTextIsMarkedAsFailed() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", TEST_FILE, "application/pdf", createPdf(""));
        when(documentRepository.count()).thenReturn(0L);
        when(cloudinaryService.uploadPdf(file)).thenReturn("public-id");
        when(cloudinaryService.getPdfUrl("public-id")).thenReturn("https://cloudinary.test/public-id.pdf");

        IllegalArgumentException thrown = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            pdfIngestionService.ingestPdf(file);
        });

        Assertions.assertEquals("No extractable text found in PDF", thrown.getMessage());
        ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentRepository, atLeastOnce()).save(captor.capture());
        Assertions.assertEquals(DocumentStatus.FAILED, captor.getValue().getStatus());
        verify(vectorStore, never()).add(any());
    }

    // builds a one page PDF in memory, an empty text gives a blank page
    private byte[] createPdf(String text) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            if (!text.isEmpty()) {
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
