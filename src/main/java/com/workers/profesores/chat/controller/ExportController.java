package com.workers.profesores.chat.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint profesional para exportar cualquier tabla a CSV o Excel.
 * 
 * Parámetros admitidos:
 *   - tabla: nombre de la tabla a exportar (obligatorio)
 *   - type: csv o xlsx (por defecto csv)
 *   - page: número de página (opcional)
 *   - size: tamaño de página (opcional)
 *   - filtro: filtro de búsqueda (opcional, puede ser JSON o string)
 *   - sort: campo de ordenación (opcional)
 *   - order: asc/desc (opcional)
 *
 * Ejemplo de uso:
 *   /api/export?tabla=alumnos&type=csv&page=1&size=100&filtro=activo&sort=nombre&order=asc
 */

import java.nio.charset.StandardCharsets;

@RestController
public class ExportController {

    @GetMapping("/api/export")
    public ResponseEntity<byte[]> exportTable(
            @RequestParam String tabla,
            @RequestParam(defaultValue = "csv") String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String filtro,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order
    ) {
        // TODO: Generar los datos reales según la tabla, filtros y formato
        // Aquí puedes usar los parámetros page, size, filtro, sort, order para filtrar los datos
        String fileName = tabla + "." + (type.equalsIgnoreCase("xlsx") ? "xlsx" : "csv");
        byte[] fileContent;
        String contentType;

        if (type.equalsIgnoreCase("xlsx")) {
            // Aquí deberías generar el Excel real según los filtros
            fileContent = ("Excel de ejemplo para " + tabla + " (filtros: " + filtro + ")").getBytes(StandardCharsets.UTF_8);
            contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        } else {
            // CSV de ejemplo según los filtros
            fileContent = ("col1,col2\nval1,val2").getBytes(StandardCharsets.UTF_8);
            contentType = "text/csv";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName);
        headers.setContentLength(fileContent.length);

        return new ResponseEntity<>(fileContent, headers, HttpStatus.OK);
    }
}
