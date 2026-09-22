package com.rivell.workbench.platform.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rivell.workbench.foundation.ApiResult;

@RestController
@RequestMapping("/api/system")
class SystemStatusController {

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiResult<SystemStatusResponse> status() {
        return ApiResult.ok(new SystemStatusResponse("rivell-backend", "ready"));
    }
}
