package kr.co.jihun.guisample.service;

import kr.co.jihun.guisample.mapper.CategoryMasterMapper;
import kr.co.jihun.guisample.dto.CategoryMasterDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * category_master 비즈니스 계층.
 *
 * <p><b>사용자 귀속</b> — 조회 조건(createUserId)은 컨트롤러가 세션에서 꺼내 param 에 담아 준다.
 * 이 계층은 세션을 알지 못하며, 저장 시 찍을 사용자명만 인자로 받는다.
 */
@Service
@RequiredArgsConstructor
public class CategoryMasterService
{
    private final CategoryMasterMapper categoryMasterMapper;

    public List<CategoryMasterDTO> selectCategoryMasterList(HashMap<String, Object> param)
    {
        return categoryMasterMapper.selectCategoryMasterList(param);
    }

    public int countCategoryMaster(HashMap<String, Object> param)
    {
        return categoryMasterMapper.countCategoryMaster(param);
    }

    /**
     * 카테고리 등록.
     *
     * @param userName 로그인 사용자명 — create_user_id 로 저장되며 이후 조회 조건의 기준이 된다
     */
    public CategoryMasterDTO createCategoryMaster(CategoryMasterDTO categoryMaster, String userName)
    {
        // category_id 는 GUID 로 서비스에서 생성
        categoryMaster.setCategoryId(UUID.randomUUID().toString());
        /* 생성자/수정자는 세션의 로그인 사용자 (create_date/update_date 는 NOW() 로 매퍼에서 처리).
           create_user_id 가 목록 조회 필터의 기준이므로 여기 값이 틀리면 등록 직후 목록에서 사라진다. */
        categoryMaster.setCreateUserId(userName);
        categoryMaster.setUpdateUserId(userName);

        categoryMasterMapper.insertCategoryMaster(categoryMaster);
        return categoryMaster;
    }
}
