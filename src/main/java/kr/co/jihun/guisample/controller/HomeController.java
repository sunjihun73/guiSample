package kr.co.jihun.guisample.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 루트 랜딩 페이지.
 *
 * 인증 없이 접근 가능하며, Keycloak 로그아웃 후 되돌아오는 지점이기도 하다
 * (post logout redirect URI = http://mytechtest.jihun.com/).
 */
@Controller
public class HomeController
{
    @GetMapping("/")
    public String home()
    {
        return "home";
    }
}
