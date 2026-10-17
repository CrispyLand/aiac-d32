package com.crispyland.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ReportProblemController {

    @GetMapping("/report-problem")
    public String reportProblemPage() {
        return "report-problem";
    }
}
