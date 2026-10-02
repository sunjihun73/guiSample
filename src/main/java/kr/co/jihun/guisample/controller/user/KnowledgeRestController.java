package kr.co.jihun.guisample.controller.user;

import kr.co.jihun.guisample.service.KnowledgeFileService;
import kr.co.jihun.guisample.dto.KnowledgeChunkDTO;
import kr.co.jihun.guisample.dto.KnowledgeFileDTO;
import kr.co.jihun.guisample.session.LoginUserSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 지식파일 목록 REST 엔드포인트.
 *
 * <p><b>사용자 귀속</b> — 조회 조건에 들어갈 로그인 사용자명은 이 컨트롤러에서
 * {@link LoginUserSession} 으로 세션에서 꺼낸다.
 */
@RestController
@RequestMapping("/user/knowledge")
@RequiredArgsConstructor
public class KnowledgeRestController
{
    private final KnowledgeFileService knowledgeFileService;
    private final LoginUserSession loginUserSession;

    /**
     * 지식파일 목록 조회. 전체 경로: GET /user/knowledge/knowledges/{categoryId}
     * jqGrid 표준 응답 shape(page/total/records/rows)으로 반환한다.
     * 경로의 categoryId 에 해당하는 카테고리의 지식파일만 조회한다(왼쪽 카테고리 선택 연동).
     *
     * @param categoryId 필터링할 카테고리 ID (경로 변수)
     * @param page       1-base 페이지 번호
     * @param rows       페이지당 행 수
     */
    @GetMapping("/knowledges/{categoryId}")
    public Map<String, Object> knowledgeList(
            @PathVariable String categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int rows)
    {
        HashMap<String, Object> param = new HashMap<>();
        if (categoryId != null && !categoryId.isBlank())
        {
            param.put("categoryId", categoryId);
        }

        /* 로그인 사용자 조건 — 총건수와 목록이 같은 조건을 봐야 하므로 count 보다 먼저 넣는다. */
        param.put("createUserId", loginUserSession.requireUserName());

        int totalRecords = knowledgeFileService.countKnowledgeFile(param);
        int totalPages   = totalRecords == 0 ? 0 : (int) Math.ceil((double) totalRecords / rows);

        param.put("startRow", (page - 1) * rows);
        param.put("pageSize", rows);

        List<KnowledgeFileDTO> list = knowledgeFileService.selectKnowledgeFileList(param);

        Map<String, Object> result = new HashMap<>();
        result.put("page",    page);
        result.put("total",   totalPages);
        result.put("records", totalRecords);
        result.put("rows",    list);
        return result;
    }

    /**
     * 선택한 지식파일의 청크 목록 조회. 전체 경로: GET /user/knowledge/knowledges/chunks/{parent_document_id}
     * vector_store 에서 metadata.parent_document_id 가 일치하는 청크를 chunk_index 오름차순으로 반환한다.
     *
     * @param parentDocumentId 선택된 지식파일 ID (knowledge_files.file_id, 경로 변수)
     * @return 청크 목록 (chunk_index ASC)
     */
    @GetMapping("/knowledges/chunks/{parentDocumentId}")
    public List<KnowledgeChunkDTO> knowledgeChunkList(@PathVariable("parentDocumentId") String parentDocumentId)
    {
        /* 소유 파일이 아니면 빈 목록 — knowledge_files 소유권으로 vector_store 접근을 막는다. */
        return knowledgeFileService.selectKnowledgeChunkList(
                parentDocumentId, loginUserSession.requireUserName());
    }
}
