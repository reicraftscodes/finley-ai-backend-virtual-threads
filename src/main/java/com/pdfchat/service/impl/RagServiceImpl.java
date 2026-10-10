package com.pdfchat.service.impl;

import com.pdfchat.constants.PromptConstant;
import com.pdfchat.model.AskResponse;
import com.pdfchat.service.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

import static com.pdfchat.constants.RagConstant.*;

@Slf4j
@Service
public class RagServiceImpl implements RagService {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ChatClient chatClient;

    @Override
    public AskResponse ask(String question) {

        // search the vector store for the most relevant document chunks based on the question
        List<Document> relevantDocs = searchVectorStore(question);

        if (relevantDocs.isEmpty()) {
            log.warn(NO_DOCS_FOUND, question);
        }

        // build context and collect sources from retrieved chunks
        String context = buildContext(relevantDocs);

        // send the context and question to OpenAI and get the answer
        String answer = callChatModel(context, question);

        // return the answer along with sources and a success status
        return AskResponse.builder()
                .outputText(answer)
                .result(true)
                .resultMessage(RESULT_SUCCESS)
                .build();
    }

    // search Pinecone for the most relevant chunks Spring AI embeds the question automatically
    private List<Document> searchVectorStore(String question) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(TOP_K)
                        .build()
        );
    }

    // join all retrieved document chunks into a single context string with metadata
    private static String buildContext(List<Document> relevantDocs) {
        return relevantDocs.stream()
                .map(doc -> {

                    String text = doc.getText();

                    String source = (String) doc.getMetadata().getOrDefault(META_SOURCE, SOURCE_UNKNOWN);

                    String type = (String) doc.getMetadata().getOrDefault(META_TYPE, "UNKNOWN");

                    String url = (String) doc.getMetadata().getOrDefault(META_URL, "");

                    return "[SOURCE: " + source + " | TYPE: " + type + (url.isBlank() ? "" : " | URL: " + url) + "]\n" + text;
                })
                .collect(Collectors.joining(CONTEXT_SEPARATOR));
    }

    // send the context and question to OpenAI using system + user prompts
    // the system prompt enforces context-only answers
    private String callChatModel(String context, String question) {
        return chatClient.prompt(
                new Prompt(List.of(
                        new SystemMessage(PromptConstant.SYSTEM_PROMPT),
                        new UserMessage(String.format(
                                PromptConstant.USER_PROMPT_TEMPLATE,
                                context,
                                question
                        ))
                ))
        ).call().content();
    }
}