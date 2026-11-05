package com.aibh.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Configuration
public class VectorStoreConfig {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Value("${spring.ai.vectorstore.simple.file-path:data/vectorstore.json}")
    private String vectorStorePath;

    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("ollamaEmbeddingClient")
    private EmbeddingClient ollamaEmbeddingClient;

    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("openAiEmbeddingClient")
    private EmbeddingClient openAiEmbeddingClient;

    @Value("${app.ai.provider:ollama}")
    private String primaryProvider;

    @Bean
    public VectorStore vectorStore() {
        EmbeddingClient selectedClient = null;
        
        if ("ollama".equalsIgnoreCase(primaryProvider) && ollamaEmbeddingClient != null) {
            selectedClient = ollamaEmbeddingClient;
            logger.info("Using Ollama for embeddings.");
        } else if (openAiEmbeddingClient != null) {
            selectedClient = openAiEmbeddingClient;
            logger.info("Using OpenAI for embeddings.");
        } else if (ollamaEmbeddingClient != null) {
            selectedClient = ollamaEmbeddingClient;
            logger.info("Using Ollama for embeddings as fallback.");
        }

        if (selectedClient == null) {
            logger.warn("⚠️ No EmbeddingClient found. VectorStore will be disabled. Auto-learning cannot function.");
            return null;
        }

        logger.info("Initializing SimpleVectorStore at path: {}", vectorStorePath);
        SimpleVectorStore vectorStore = new SimpleVectorStore(selectedClient);
        File vectorStoreFile = new File(vectorStorePath);
        if (vectorStoreFile.exists()) {
            vectorStore.load(vectorStoreFile);
            logger.info("Loaded existing vector store from file.");
        }
        return vectorStore;
    }
}
