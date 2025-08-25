package com.workers.profesores.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.workers.profesores.chat.client.AcademiaClient;
import com.workers.profesores.chat.model.openai.ToolCall;
import com.workers.profesores.chat.model.openai.ToolOutput;
import org.springframework.stereotype.Service;

@Service
public class ToolsDispatcher {
    @org.springframework.beans.factory.annotation.Value("${backend.debug:false}")
    private boolean debug;
    // Métodos legacy eliminados: getAlumnos(), getTotalAlumnos()
    private final AcademiaClient academiaClient;

    public ToolsDispatcher(AcademiaClient academiaClient) {
        this.academiaClient = academiaClient;
    }

    public ToolOutput execute(ToolCall call) {
        if (debug) {
            System.out.println("[ToolsDispatcher][DEBUG] execute llamado con ToolCall: " + call);
            System.out.println("[ToolsDispatcher][DEBUG] Nombre de la tool: " + call.getName() + ", args: " + call.getArgs());
        }
        ToolOutput output;
        switch (call.getName()) {
            case "healthCheck":
                output = ToolOutput.of(call, academiaClient.healthCheck());
                break;
            case "getTurnosLibres":
                output = ToolOutput.of(call, academiaClient.getTurnosLibres());
                break;
            case "inscribirAlumno":
                output = ToolOutput.of(call, academiaClient.inscribirAlumno(call.getArgs()));
                break;
            case "registrarPago":
                output = ToolOutput.of(call, academiaClient.registrarPago(call.getArgs()));
                break;
            case "getTurnosActivos":
                output = ToolOutput.of(call, academiaClient.getTurnosActivos());
                break;
            case "getTarifas":
                output = ToolOutput.of(call, academiaClient.getTarifas());
                break;
            case "crearTarifa":
                output = ToolOutput.of(call, academiaClient.crearTarifa(call.getArgs()));
                break;
            case "actualizarTarifa":
                output = ToolOutput.of(call, academiaClient.actualizarTarifa(call.getArgs().get("tarifa_id").asText(), call.getArgs()));
                break;
            case "eliminarTarifa":
                output = ToolOutput.of(call, academiaClient.eliminarTarifa(call.getArgs().get("tarifa_id").asText()));
                break;
            case "getAlumnosTurno":
                output = ToolOutput.of(call, academiaClient.getAlumnosTurno(call.getArgs().get("turno_id").asText()));
                break;
            case "bajaInscripcion":
                output = ToolOutput.of(call, academiaClient.bajaInscripcion(call.getArgs().get("inscripcion_id").asText()));
                break;
            case "getAlumnos":
                output = ToolOutput.of(call, academiaClient.getAlumnos());
                break;
            case "getAlumnoById":
                output = ToolOutput.of(call, academiaClient.getAlumnoById(call.getArgs().get("alumno_id").asText()));
                break;
            case "buscarAlumnosPorNombre":
                output = ToolOutput.of(call, academiaClient.buscarAlumnosPorNombre(call.getArgs().get("nombre").asText()));
                break;
            case "crearEmpresa":
                output = ToolOutput.of(call, academiaClient.crearEmpresa(call.getArgs()));
                break;
            case "getEmpresas":
                output = ToolOutput.of(call, academiaClient.getEmpresas());
                break;
            case "getEmpresaById":
                output = ToolOutput.of(call, academiaClient.getEmpresaById(call.getArgs().get("empresa_id").asText()));
                break;
            default:
                output = ToolOutput.of(call, "Tool no implementada");
        }
        if (debug) {
            System.out.println("[ToolsDispatcher][DEBUG] Salida de execute: " + output);
        }
        return output;
    }
}
