package top.bootz.security.web.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("")
public class IndexController {

    @GetMapping(value = { "", "/" })
    public String index() {
        return "redirect:/index.html";
    }

}
