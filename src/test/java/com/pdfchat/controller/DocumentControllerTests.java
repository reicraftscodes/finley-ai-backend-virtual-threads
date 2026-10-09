package com.pdfchat.controller;

import com.pdfchat.model.AskResponse;
import com.pdfchat.model.UploadResponse;
import com.pdfchat.service.MultiImageIngestionService;
import com.pdfchat.service.PdfIngestionService;
import com.pdfchat.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class DocumentControllerTests {

    @Mock
    private PdfIngestionService pdfIngestionService;

    @Mock
    private MultiImageIngestionService multiImageIngestionService;

    @Mock
    private RagService ragService;

    @InjectMocks
    private DocumentController documentController;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(documentController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    public void uploadPdfReturnsOkWithResponse() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile("pdf", "policy.pdf", "application/pdf", "pdf bytes".getBytes());
        UploadResponse uploadResponse = UploadResponse.builder()
                .status("Success")
                .indexedChunks(12)
                .file("policy.pdf")
                .build();
        when(pdfIngestionService.ingestPdf(any(MultipartFile.class))).thenReturn(uploadResponse);

        mockMvc.perform(multipart("/api/documents/upload/pdf").file(pdf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Success"))
                .andExpect(jsonPath("$.indexedChunks").value(12))
                .andExpect(jsonPath("$.file").value("policy.pdf"));
    }

    @Test
    public void uploadPdfWhenLimitReachedReturnsBadRequest() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile("pdf", "policy.pdf", "application/pdf", "pdf bytes".getBytes());
        when(pdfIngestionService.ingestPdf(any(MultipartFile.class))).thenThrow(new IllegalArgumentException("Upload limit reached"));

        mockMvc.perform(multipart("/api/documents/upload/pdf").file(pdf))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Upload limit reached"))
                .andExpect(jsonPath("$.path").value("/api/documents/upload/pdf"));
    }

    @Test
    public void uploadPdfWhenIngestionFailsReturnsServerError() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile("pdf", "policy.pdf", "application/pdf", "pdf bytes".getBytes());
        when(pdfIngestionService.ingestPdf(any(MultipartFile.class))).thenThrow(new IOException("Disk full"));

        mockMvc.perform(multipart("/api/documents/upload/pdf").file(pdf))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Disk full"));
    }

    @Test
    public void uploadImagesReturnsOkWithResponse() throws Exception {
        MockMultipartFile image1 = new MockMultipartFile("files", "one.png", "image/png", "image bytes".getBytes());
        MockMultipartFile image2 = new MockMultipartFile("files", "two.png", "image/png", "image bytes".getBytes());
        UploadResponse uploadResponse = UploadResponse.builder()
                .status("Success")
                .indexedChunks(2)
                .file("one.png")
                .build();
        when(multiImageIngestionService.ingestImages(any(MultipartFile[].class))).thenReturn(uploadResponse);

        mockMvc.perform(multipart("/api/documents/upload/images").file(image1).file(image2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Success"))
                .andExpect(jsonPath("$.indexedChunks").value(2));
    }

    @Test
    public void uploadImagesWithBadImageReturnsBadRequest() throws Exception {
        MockMultipartFile image = new MockMultipartFile("files", "notes.txt", "text/plain", "text".getBytes());
        when(multiImageIngestionService.ingestImages(any(MultipartFile[].class)))
                .thenThrow(new IllegalArgumentException("Only PNG, JPG, JPEG, WEBP images allowed"));

        mockMvc.perform(multipart("/api/documents/upload/images").file(image))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only PNG, JPG, JPEG, WEBP images allowed"));
    }

    @Test
    public void askReturnsAnswer() throws Exception {
        AskResponse askResponse = AskResponse.builder().outputText("Passwords must be 12 characters.").result(true).build();
        when(ragService.ask("What is the password policy?")).thenReturn(askResponse);

        mockMvc.perform(post("/api/documents/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"What is the password policy?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputText").value("Passwords must be 12 characters."))
                .andExpect(jsonPath("$.result").value(true));
    }

    @Test
    public void askTrimsQuestionBeforeCallingService() throws Exception {
        AskResponse askResponse = AskResponse.builder().outputText("An answer").result(true).build();

        when(ragService.ask("What is the policy?")).thenReturn(askResponse);

        mockMvc.perform(post("/api/documents/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"   What is the policy?   \"}"))
                .andExpect(status().isOk());

        verify(ragService).ask("What is the policy?");
    }
}