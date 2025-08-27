package com.workers.profesores.chat.aggregate;

import java.util.List;

public record ListResponse<T>(
        String mode,          // "paged" | "all" | "export"
        long total,
        Integer page,
        Integer size,
        List<T> items,
        String message,
        String downloadUrl
) {}
