package com.workers.profesores.chat.service;

import com.workers.profesores.chat.model.openai.ToolSchema;
import java.util.List;
import java.util.Map;

public class ToolsRegistry {
    public static List<Map<String, Object>> getToolSchemas() {
        return List.of(
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "getAlumnoById",
                    "description", "Obtiene el detalle de un alumno",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "id", Map.of(
                                "type", "string",
                                "description", "ID del alumno"
                            )
                        ),
                        "required", List.of("id")
                    )
                )
            ),
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "listAlumnos",
                    "description", "Devuelve la lista de alumnos inscritos en la academia. Puedes filtrar por nombre, limitar la cantidad, pedir solo los primeros, los últimos, o un rango específico. Ejemplos: 'los 10 primeros', 'los 5 últimos', 'del 10 al 20', etc.",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "limit", Map.of("type", "integer", "description", "Cantidad máxima de alumnos a devolver (por ejemplo, 10 para los 10 primeros)"),
                            "offset", Map.of("type", "integer", "description", "Número de alumnos a saltar desde el inicio (por ejemplo, 10 para empezar desde el alumno 11)"),
                            "from", Map.of("type", "integer", "description", "Índice de inicio del rango (1-based, por ejemplo, 10 para empezar desde el alumno 10)"),
                            "to", Map.of("type", "integer", "description", "Índice de fin del rango (por ejemplo, 20 para terminar en el alumno 20)"),
                            "last", Map.of("type", "integer", "description", "Cantidad de alumnos desde el final (por ejemplo, 5 para los 5 últimos)"),
                            "q", Map.of("type", "string", "description", "Filtro opcional por nombre")
                        ),
                        "required", List.of()
                    )
                )
            )
        );
    }
}
