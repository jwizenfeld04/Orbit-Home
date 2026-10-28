package home.jw.orbit;

/**
 * Android only lets an app see which music is playing (media sessions) if it holds notification-listener
 * access. Orbit doesn't read notifications; this empty service just carries that permission. Grant it once:
 *   adb shell cmd notification allow_listener home.jw.orbit/home.jw.orbit.MediaListener
 */
public class MediaListener extends android.service.notification.NotificationListenerService {
}
