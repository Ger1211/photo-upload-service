package com.wedding.upload.dto;

import java.util.List;

public record GalleryResponse(
        List<GalleryPhoto> photos,
        long total,
        int page,
        int size,
        boolean hasMore
) {}
