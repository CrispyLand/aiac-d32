package com.crispyland.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SupportPageController {

    @GetMapping("/support")
    public String supportPage() {
        return "support";
    }
}
