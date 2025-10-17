package com.workers.profesores.chat.auth;

import java.util.List;

public class UserClaims {
    public final Long usuarioId;
    public final List<String> roles;
    public final Integer academiaId;
    public final Integer profesorUsuarioId;
    public final String actor;
    public final Integer tokenVersion;
    public final String displayName;
    public UserClaims(Long usuarioId, List<String> roles, Integer academiaId, Integer profesorUsuarioId) {
        this(usuarioId, roles, academiaId, profesorUsuarioId, null, null, null);
    }

    public UserClaims(Long usuarioId, List<String> roles, Integer academiaId, Integer profesorUsuarioId, Integer tokenVersion) {
        this(usuarioId, roles, academiaId, profesorUsuarioId, null, tokenVersion, null);
    }

    // Backward-compatible constructor (used by existing tests): without displayName
    public UserClaims(Long usuarioId, List<String> roles, Integer academiaId, Integer profesorUsuarioId, String actor, Integer tokenVersion) {
        this(usuarioId, roles, academiaId, profesorUsuarioId, actor, tokenVersion, null);
    }

    public UserClaims(Long usuarioId, List<String> roles, Integer academiaId, Integer profesorUsuarioId, String actor, Integer tokenVersion, String displayName) {
        this.usuarioId = usuarioId;
        this.roles = roles;
        this.academiaId = academiaId;
        this.profesorUsuarioId = profesorUsuarioId;
        this.actor = actor;
        this.tokenVersion = tokenVersion;
        this.displayName = displayName;
    }

    public boolean isAdminPlataforma() { return roles != null && roles.contains("Admin_plataforma"); }
    public boolean isAdminAcademia() { return roles != null && roles.contains("Admin_academia"); }
    public boolean isProfesorAcademia() { return roles != null && roles.contains("Profesor_academia"); }
}
