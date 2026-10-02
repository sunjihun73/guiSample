package kr.co.jihun.guisample.mapper;

import kr.co.jihun.guisample.dto.KnowledgeChunkDTO;
import kr.co.jihun.guisample.dto.KnowledgeFileDTO;
import org.apache.ibatis.annotations.Mapper;

import java.util.HashMap;
import java.util.List;

@Mapper
public interface KnowledgeFileMapper
{
    List<KnowledgeFileDTO> selectKnowledgeFileList(HashMap<String, Object> param);

    int countKnowledgeFile(HashMap<String, Object> param);

    int insertKnowledgeFile(KnowledgeFileDTO knowledgeFile);

    /**
     * 특정 지식파일(parent_document_id)에 속한 청크 목록을 chunk_index 오름차순으로 조회한다.
     *
     * @param param parentDocumentId(필수)
     */
    List<KnowledgeChunkDTO> selectKnowledgeChunkList(HashMap<String, Object> param);

    /**
     * 로그인 사용자가 소유한 지식파일 id 목록.
     *
     * <p>dense 벡터 검색의 소유권 필터 재료다. 청크 metadata 에는 사용자 키가 없어
     * 메타데이터 필터만으로는 소유자를 가릴 수 없으므로, 소유한 file_id 를 먼저 뽑아
     * {@code parent_document_id IN (...)} 으로 건다.
     *
     * @param param createUserId(필수), categoryId(선택)
     */
    List<String> selectOwnedFileIdList(HashMap<String, Object> param);
}
