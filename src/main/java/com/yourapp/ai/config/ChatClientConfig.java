package com.yourapp.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean(name = "plannerChatClient")
    public ChatClient plannerChatClient(
            OllamaChatModel chatModel,
            @Value("${app.models.planner:llama3.2:3b}") String plannerModel) {
        return ChatClient.builder(chatModel)
                .defaultOptions(OllamaChatOptions.builder().model(plannerModel).build())
                .build();
    }

    @Bean(name = "answerChatClient")
    public ChatClient answerChatClient(
            OllamaChatModel chatModel,
            @Value("${app.models.answer:mistral:7b-instruct}") String answerModel) {
        return ChatClient.builder(chatModel)
                .defaultOptions(OllamaChatOptions.builder().model(answerModel).build())
                .build();
    }
}
