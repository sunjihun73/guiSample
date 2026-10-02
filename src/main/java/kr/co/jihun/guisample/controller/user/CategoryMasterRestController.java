package kr.co.jihun.guisample.controller.user;

import kr.co.jihun.guisample.service.CategoryMasterService;
import kr.co.jihun.guisample.dto.CategoryMasterDTO;
import kr.co.jihun.guisample.session.LoginUserSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * category_master REST 엔드포인트.
 *
 * <p><b>사용자 귀속</b> — 조회 조건에 들어갈 로그인 사용자명은 이 컨트롤러에서
 * {@link LoginUserSession} 으로 세션에서 꺼내 param 에 담는다.
 */
@RestController
@RequestMapping("/user/category")
@RequiredArgsConstructor
public class CategoryMasterRestController
{
    private final CategoryMasterService categoryMasterService;
    private final LoginUserSession loginUserSession;

    @GetMapping("/categories")
    public Map<String, Object> categoryList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int rows)
    {
        HashMap<String, Object> param = new HashMap<>();

        /* 로그인 사용자 조건 — 총건수와 목록이 같은 조건을 봐야 하므로 count 보다 먼저 넣는다. */
        param.put("createUserId", loginUserSession.requireUserName());

        int totalRecords = categoryMasterService.countCategoryMaster(param);
        int totalPages   = totalRecords == 0 ? 0 : (int) Math.ceil((double) totalRecords / rows);

        param.put("startRow", (page - 1) * rows);
        param.put("pageSize", rows);

        List<CategoryMasterDTO> list = categoryMasterService.selectCategoryMasterList(param);

        Map<String, Object> result = new HashMap<>();
        result.put("page",    page);
        result.put("total",   totalPages);
        result.put("records", totalRecords);
        result.put("rows",    list);
        return result;
    }

    @PostMapping("/categories")
    public Map<String, Object> saveCategory(@RequestBody CategoryMasterDTO categoryMaster)
    {
        Map<String, Object> result = new HashMap<>();
        try
        {
            /* create_user_id 에 세션 사용자명을 찍어야 등록 직후 목록 조회 조건에 걸린다. */
            CategoryMasterDTO saved = categoryMasterService.createCategoryMaster(
                    categoryMaster, loginUserSession.requireUserName());
            result.put("success",    true);
            result.put("categoryId", saved.getCategoryId());
        }
        catch (Exception e)
        {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
