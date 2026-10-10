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
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static com.pdfchat.constants.DocumentIngestionConstant.DEFAULT_FILENAME;
import static com.pdfchat.constants.DocumentIngestionConstant.MAX_PDF_LIMIT;
import static com.pdfchat.constants.DocumentIngestionConstant.UPLOAD_DIR;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PdfIngestionServiceTests {

    private static final String TEST_FILE = "unit-test-policy.pdf";
    private static final String TEST_FILE_WITH_SPACES = "unit-test policy (1).pdf";
    private static final String TEST_FILE_SANITISED = "unit-test_policy__1_.pdf";
    private static final String MOCK_PUBLIC_ID = "public-id";
    private static final String MOCK_CLOUDINARY_URL = "https://cloudinary.test/public-id.pdf";
    private static final String REAL_POLICY_PDF = "pdfs/Information-Security-Policy.pdf";
    private static final String TEST_FILE_REAL_POLICY = "unit-test-real-policy.pdf";

    @Mock
    private VectorStore vectorStore;

    @Mock
    private CloudinaryService cloudinaryService;

    @Mock
    private DocumentRepository documentRepository;

    @Captor
    private ArgumentCaptor<List<Document>> chunksCaptor;

    @InjectMocks
    private PdfIngestionServiceImpl pdfIngestionService;

    @AfterEach
    public void deleteSavedTestFiles() throws IOException {
        Files.deleteIfExists(Paths.get(UPLOAD_DIR, TEST_FILE));
        Files.deleteIfExists(Paths.get(UPLOAD_DIR, TEST_FILE_SANITISED));
        Files.deleteIfExists(Paths.get(UPLOAD_DIR, TEST_FILE_REAL_POLICY));
        // the default filename is used when a PDF has no name, so the saved copy is removed too
        Files.deleteIfExists(Paths.get(UPLOAD_DIR, DEFAULT_FILENAME));
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
        when(cloudinaryService.uploadPdf(file)).thenReturn(MOCK_PUBLIC_ID);
        when(cloudinaryService.getPdfUrl(MOCK_PUBLIC_ID)).thenReturn(MOCK_CLOUDINARY_URL);

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
        when(cloudinaryService.uploadPdf(file)).thenReturn(MOCK_PUBLIC_ID);
        when(cloudinaryService.getPdfUrl(MOCK_PUBLIC_ID)).thenReturn(MOCK_CLOUDINARY_URL);

        pdfIngestionService.ingestPdf(file);

        // the same record is saved twice, first as PROCESSING and then as READY
        ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentRepository, times(2)).save(captor.capture());
        DocumentEntity savedRecord = captor.getValue();
        Assertions.assertEquals(TEST_FILE, savedRecord.getFilename());
        Assertions.assertEquals(MOCK_PUBLIC_ID, savedRecord.getCloudinaryPublicId());
        Assertions.assertEquals(MOCK_CLOUDINARY_URL, savedRecord.getCloudinaryUrl());
        Assertions.assertEquals(DocumentStatus.READY, savedRecord.getStatus());
    }

    @Test
    public void filenameWithSpecialCharactersIsSanitised() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", TEST_FILE_WITH_SPACES, "application/pdf",
                createPdf("This is a sample information security policy for the company."));
        when(documentRepository.count()).thenReturn(0L);
        when(cloudinaryService.uploadPdf(file)).thenReturn(MOCK_PUBLIC_ID);
        when(cloudinaryService.getPdfUrl(MOCK_PUBLIC_ID)).thenReturn(MOCK_CLOUDINARY_URL);

        UploadResponse response = pdfIngestionService.ingestPdf(file);

        Assertions.assertEquals(TEST_FILE_SANITISED, response.getFile());
    }

    @Test
    public void blankFilenameUsesDefaultFilename() throws IOException {
        // a blank original filename should be replaced with the default one (upload.pdf)
        MockMultipartFile file = new MockMultipartFile("file", "   ", "application/pdf",
                createPdf("This is a sample information security policy for the company."));
        when(documentRepository.count()).thenReturn(0L);
        when(cloudinaryService.uploadPdf(file)).thenReturn(MOCK_PUBLIC_ID);
        when(cloudinaryService.getPdfUrl(MOCK_PUBLIC_ID)).thenReturn(MOCK_CLOUDINARY_URL);

        UploadResponse response = pdfIngestionService.ingestPdf(file);

        Assertions.assertEquals(DEFAULT_FILENAME, response.getFile());
    }

    @Test
    public void nullFilenameUsesDefaultFilename() throws IOException {
        // MockMultipartFile turns a null name into an empty one, so a plain mock is used to get a real null
        MultipartFile file = mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(
                createPdf("This is a sample information security policy for the company.")));
        when(documentRepository.count()).thenReturn(0L);
        when(cloudinaryService.uploadPdf(file)).thenReturn(MOCK_PUBLIC_ID);
        when(cloudinaryService.getPdfUrl(MOCK_PUBLIC_ID)).thenReturn(MOCK_CLOUDINARY_URL);

        UploadResponse response = pdfIngestionService.ingestPdf(file);

        Assertions.assertEquals(DEFAULT_FILENAME, response.getFile());
    }

    @Test
    public void existingVectorsAreClearedBeforeIngesting() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", TEST_FILE, "application/pdf",
                createPdf("This is a sample information security policy for the company."));
        Document oldDocument = new Document("Old document text");
        when(documentRepository.count()).thenReturn(0L);
        when(cloudinaryService.uploadPdf(file)).thenReturn(MOCK_PUBLIC_ID);
        when(cloudinaryService.getPdfUrl(MOCK_PUBLIC_ID)).thenReturn(MOCK_CLOUDINARY_URL);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(oldDocument));

        pdfIngestionService.ingestPdf(file);

        verify(vectorStore).delete(List.of(oldDocument.getId()));
    }

    @Test
    public void pdfWithNoTextIsMarkedAsFailed() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", TEST_FILE, "application/pdf", createPdf(""));
        when(documentRepository.count()).thenReturn(0L);
        when(cloudinaryService.uploadPdf(file)).thenReturn(MOCK_PUBLIC_ID);
        when(cloudinaryService.getPdfUrl(MOCK_PUBLIC_ID)).thenReturn(MOCK_CLOUDINARY_URL);

        IllegalArgumentException thrown = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            pdfIngestionService.ingestPdf(file);
        });

        Assertions.assertEquals("No extractable text found in PDF", thrown.getMessage());
        ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentRepository, atLeastOnce()).save(captor.capture());
        Assertions.assertEquals(DocumentStatus.FAILED, captor.getValue().getStatus());
        verify(vectorStore, never()).add(any());
    }

    @Test
    public void realPolicyPdfIsSplitIntoTaggedChunks() throws IOException {
        // reads the real PDF from src/main/resources/pdfs, uploaded under a test filename so no real upload is overwritten
        byte[] realPdf = new ClassPathResource(REAL_POLICY_PDF).getInputStream().readAllBytes();
        MockMultipartFile file = new MockMultipartFile("file", TEST_FILE_REAL_POLICY, "application/pdf", realPdf);
        when(documentRepository.count()).thenReturn(0L);
        when(cloudinaryService.uploadPdf(file)).thenReturn(MOCK_PUBLIC_ID);
        when(cloudinaryService.getPdfUrl(MOCK_PUBLIC_ID)).thenReturn(MOCK_CLOUDINARY_URL);

        UploadResponse response = pdfIngestionService.ingestPdf(file);

        verify(vectorStore).add(chunksCaptor.capture());
        List<Document> chunks = chunksCaptor.getValue();
        // the policy is 12 pages long so it must produce more than one chunk
        Assertions.assertTrue(chunks.size() > 1);
        Assertions.assertEquals(chunks.size(), response.getIndexedChunks());
        // every chunk is tagged with where it came from
        for (Document chunk : chunks) {
            Assertions.assertEquals(TEST_FILE_REAL_POLICY, chunk.getMetadata().get("source"));
            Assertions.assertEquals(MOCK_CLOUDINARY_URL, chunk.getMetadata().get("cloudinary_url"));
            Assertions.assertNotNull(chunk.getMetadata().get("page"));
        }
        // the real text of the policy was extracted
        // the real text of the policy was extracted (spaces, line breaks and odd characters are ignored)
        boolean containsPolicyText = chunks.stream()
                .anyMatch(chunk -> chunk.getText().toLowerCase().replaceAll("[^a-z]", "").contains("informationsecurity"));
        Assertions.assertTrue(containsPolicyText, "No chunk contained 'information security'. First chunk was: [" + chunks.get(0).getText() + "]");
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