package com.soarer.alert.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SiteManifestController REST 控制器。
 */
@RestController
public class SiteManifestController {

    @GetMapping("/site.webmanifest")
    public ResponseEntity<Resource> siteManifest() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/manifest+json"))
                .body(new ClassPathResource("static/site.webmanifest"));
    }
}
