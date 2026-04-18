package me.bestdad.autoPluginUpdater.domain;

public record ActionResult(boolean success, String message, PluginView pluginView, boolean restartPromptSuggested) {

    public static ActionResult success(String message) {
        return new ActionResult(true, message, null, false);
    }

    public static ActionResult success(String message, PluginView pluginView, boolean restartPromptSuggested) {
        return new ActionResult(true, message, pluginView, restartPromptSuggested);
    }

    public static ActionResult failure(String message) {
        return new ActionResult(false, message, null, false);
    }
}
