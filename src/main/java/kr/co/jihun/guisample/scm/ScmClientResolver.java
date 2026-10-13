package kr.co.jihun.guisample.scm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 저장소 URL 에 맞는 {@link ScmClient} 를 고른다.
 * 구현체는 빈으로 등록되기만 하면 여기서 자동으로 후보에 들어간다.
 */
@Component
@RequiredArgsConstructor
public class ScmClientResolver
{
    private final List<ScmClient> clients;

    public ScmClient resolve(String repoUrl)
    {
        return clients.stream()
                .filter(client -> client.supports(repoUrl))
                .findFirst()
                .orElseThrow(() -> new ScmException("지원하지 않는 저장소 URL 입니다: " + repoUrl));
    }
}
