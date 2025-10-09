// Archivo archivado: ResourceType.java
package com.workers.profesores.chat.aggregate;

import java.util.Arrays;
import java.util.Optional;

public enum ResourceType {
        ALUMNOS(
                "alumnos",
                "/vlodeiro/secretaria/alumnos", // endpoint real academia
                true,   // soporta paginación
                "page",
                "size",
                50      // tamaño de página
        ),
        TARIFAS(
                "tarifas",
                "/vlodeiro/secretaria/tarifas",
                false,
                null, null, 0
        ),
        EMPRESAS(
                "empresa",   // coincide con /vlodeiro/empresa/empresa
                "/vlodeiro/empresa/empresa",
                false,
                null, null, 0
        ),
        TURNOS(
                "turnos",
                "/vlodeiro/secretaria/turnos",
                false,
                null, null, 0
        );


        private final String resourceKey;
        private final String academiaPath;
        private final boolean paginated;
        private final String pageParam;
        private final String sizeParam;
        private final int defaultPageSize;

        ResourceType(String resourceKey, String academiaPath, boolean paginated, String pageParam, String sizeParam, int defaultPageSize) {
                this.resourceKey = resourceKey;
                this.academiaPath = academiaPath;
                this.paginated = paginated;
                this.pageParam = pageParam;
                this.sizeParam = sizeParam;
                this.defaultPageSize = defaultPageSize;
        }

        public String getResourceKey() { return resourceKey; }
        public String getAcademiaPath() { return academiaPath; }
        public boolean isPaginated() { return paginated; }
        public String getPageParam() { return pageParam; }
        public String getSizeParam() { return sizeParam; }
        public int getDefaultPageSize() { return defaultPageSize; }

        public static Optional<ResourceType> fromKey(String key) {
                return Arrays.stream(values())
                                .filter(r -> r.resourceKey.equalsIgnoreCase(key))
                                .findFirst();
        }
}
