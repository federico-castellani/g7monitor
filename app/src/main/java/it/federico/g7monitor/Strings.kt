package it.federico.g7monitor

import androidx.annotation.StringRes

/**
 * Text in the system language, for code outside Compose (services, notifications, the event
 * log). Screens use stringResource. In JVM unit tests there are no resources: the key's id and
 * the arguments are returned, enough for the messages that only end up in the log.
 */
fun str(@StringRes id: Int, vararg args: Any?): String =
    if (G7App.ready) G7App.instance.getString(id, *args) else "#$id ${args.joinToString()}".trim()
