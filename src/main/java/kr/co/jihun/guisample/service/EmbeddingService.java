package kr.co.jihun.guisample.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 텍스트 청킹·임베딩 적재 서비스.
 * plain text(UTF-8) 본문을 800토큰 단위로 청킹한 뒤, EmbeddingModel 임베딩을 거쳐
 * pgvector VectorStore 에 적재한다.
 * (LLM 호출 없이 EmbeddingModel 만 사용 — VectorStore.add() 내부에서 자동 호출)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService
{
    private final VectorStore vectorStore;

    /** 청킹 단위(토큰). 요구사항 명시값. */
    private static final int CHUNK_SIZE = 800;

    /**
     * 본문 텍스트를 청킹·임베딩하여 vector_store 에 적재한다.
     * <p>
     * 부모 Document 의 id 를 {@code fileId} 로 지정한다. TokenTextSplitter 는 청킹 시
     * 각 청크 Document 의 metadata 에 부모 id 를 {@code parent_document_id} 키로 자동 주입하므로,
     * 적재된 모든 청크의 {@code metadata.parent_document_id} 가 knowledge_files.file_id 와 일치한다.
     * 청크 자신의 id 는 청크마다 새 UUID 가 자동 부여된다(요구사항 7.5).
     * metadata 에는 {@code {"source": categoryId}} 가 들어간다(요구사항 7.7).
     *
     * @param text       임베딩할 본문(UTF-8)
     * @param categoryId metadata.source 로 저장할 카테고리 ID
     * @param fileId     부모 Document id = vector_store 청크의 parent_document_id (knowledge_files.file_id 와 동일)
     * @return 적재된 청크 수 (저장할 청크가 없으면 0)
     */
    public int embed(String text, String categoryId, String fileId)
    {
        if (text == null || text.isBlank())
        {
            return 0;
        }

        // 부모 Document — id 를 fileId 로 명시 지정. metadata.source = categoryId.
        Document parent = new Document(fileId, text, Map.of("category_id", categoryId));

        // 800토큰 청킹 (무인자 생성자 deprecated → 빌더 사용)
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .build();
        List<Document> chunks = splitter.apply(List.of(parent));

        // 최소 청크 길이 미만 등으로 청크가 0건일 수 있음
        if (chunks == null || chunks.isEmpty())
        {
            return 0;
        }

        // 벡터 저장 (EmbeddingModel 호출 후 id(UUID)+content+metadata+embedding insert)
        // 각 청크 metadata = {"source": categoryId, "parent_document_id": fileId}
        vectorStore.add(chunks);

        int chunkCount = chunks.size();
        log.info("임베딩 적재 완료 - fileId={}, categoryId={}, chunkCount={}", fileId, categoryId, chunkCount);
        return chunkCount;
    }
}
