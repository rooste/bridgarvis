package com.bridgarvis.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/** https://mail.<domain>/ → the mail UI. (Only browser certificates get this far; see ClientRoleFilter.) */
@Controller
class RootController {
    @GetMapping("/")
    fun root() = "redirect:/ui"
}
