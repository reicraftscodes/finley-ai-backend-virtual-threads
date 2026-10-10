package com.pdfchat.service;

import com.pdfchat.constants.PromptConstant;
import com.pdfchat.model.AskResponse;
import com.pdfchat.service.impl.RagServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static com.pdfchat.constants.RagConstant.CONTEXT_SEPARATOR;
import static com.pdfchat.constants.RagConstant.RESULT_SUCCESS;
import static com.pdfchat.constants.RagConstant.TOP_K;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceTests {

    private static final String QUESTION = "What is the password policy?";
    private static final String FAKE_ANSWER = "Passwords must be at least 12 characters.";
    private static final String FAKE_URL = "https://cloudinary.test/policy.pdf";

    @Mock
    private VectorStore vectorStore;

    // deep stubs let the mock answer the chained call chatClient.prompt(...).call().content()
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    @InjectMocks
    private RagServiceImpl ragService;

    @Test
    public void askReturnsAnswerFromChatModel() {
        Document doc = new Document("Passwords need 12 characters.", Map.of("source", "policy.pdf"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(FAKE_ANSWER);

        AskResponse response = ragService.ask(QUESTION);

        Assertions.assertEquals(FAKE_ANSWER, response.getOutputText());
        Assertions.assertTrue(response.getResult());
        Assertions.assertEquals(RESULT_SUCCESS, response.getResultMessage());
    }

    @Test
    public void askSearchesVectorStoreWithQuestionAndTopK() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(FAKE_ANSWER);

        ragService.ask(QUESTION);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        Assertions.assertEquals(QUESTION, captor.getValue().getQuery());
        Assertions.assertEquals(TOP_K, captor.getValue().getTopK());
    }

    @Test
    public void askSendsSystemPromptFirstThenUserPrompt() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(FAKE_ANSWER);

        ragService.ask(QUESTION);

        Prompt prompt = capturePrompt();
        Assertions.assertEquals(2, prompt.getInstructions().size());
        Assertions.assertEquals(PromptConstant.SYSTEM_PROMPT, prompt.getInstructions().get(0).getText());
        Assertions.assertTrue(prompt.getInstructions().get(1).getText().contains(QUESTION));
    }

    @Test
    public void contextIncludesSourceTypeAndUrl() {
        Document doc = new Document("Passwords need 12 characters.",
                Map.of("source", "policy.pdf", "type", "PDF", "cloudinary_url", FAKE_URL));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(FAKE_ANSWER);

        ragService.ask(QUESTION);

        String userMessage = getUserMessage(capturePrompt());
        Assertions.assertTrue(userMessage.contains(
                "[SOURCE: policy.pdf | TYPE: PDF | URL: " + FAKE_URL + "]\nPasswords need 12 characters."));
    }

    @Test
    public void contextLeavesOutUrlWhenBlank() {
        Document doc = new Document("Passwords need 12 characters.",
                Map.of("source", "policy.pdf", "type", "PDF", "cloudinary_url", ""));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(FAKE_ANSWER);

        ragService.ask(QUESTION);

        String userMessage = getUserMessage(capturePrompt());

        Assertions.assertTrue(userMessage.contains("[SOURCE: policy.pdf | TYPE: PDF]\nPasswords need 12 characters."));
        Assertions.assertFalse(userMessage.contains("URL:"));
    }

    @Test
    public void contextUsesDefaultsWhenMetadataIsMissing() {
        Document doc = new Document("Passwords need 12 characters.");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(FAKE_ANSWER);

        ragService.ask(QUESTION);

        String userMessage = getUserMessage(capturePrompt());
        Assertions.assertTrue(userMessage.contains("[SOURCE: unknown | TYPE: UNKNOWN]\nPasswords need 12 characters."));
    }

    @Test
    public void contextJoinsAllDocumentsWithSeparator() {
        Document first = new Document("First chunk.", Map.of("source", "one.pdf", "type", "PDF"));
        Document second = new Document("Second chunk.", Map.of("source", "two.pdf", "type", "PDF"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(first, second));
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(FAKE_ANSWER);

        ragService.ask(QUESTION);

        String userMessage = getUserMessage(capturePrompt());
        String expectedContext = "[SOURCE: one.pdf | TYPE: PDF]\nFirst chunk."
                + CONTEXT_SEPARATOR
                + "[SOURCE: two.pdf | TYPE: PDF]\nSecond chunk.";
        Assertions.assertTrue(userMessage.contains(expectedContext));
    }

    @Test
    public void askStillCallsChatModelWhenNoDocumentsFound() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn("The answer is not available in the provided document.");

        AskResponse response = ragService.ask(QUESTION);

        // the context is empty but the model is still asked, so it can reply that there is no answer
        Assertions.assertEquals("The answer is not available in the provided document.", response.getOutputText());
        Assertions.assertTrue(response.getResult());
        Assertions.assertTrue(getUserMessage(capturePrompt()).startsWith("Context:\n\n"));
    }

    // gets the Prompt that RagServiceImpl sent to the chat model
    private Prompt capturePrompt() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        // atLeastOnce because setting up the deep stub in the test also counts as a call, the last one is the real call
        verify(chatClient, atLeastOnce()).prompt(captor.capture());
        return captor.getValue();
    }

    // the second message in the prompt is the user message that holds the context and question
    private String getUserMessage(Prompt prompt) {
        return prompt.getInstructions().get(1).getText();
    }
}