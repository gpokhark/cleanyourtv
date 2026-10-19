# Remote buttons that open apps

Many remotes have app buttons (YouTube, Netflix, Prime Video...). On Google TV the stock launcher and a few TV-maker
packages handle them, so after the stock launcher is disabled some buttons do nothing. The launcher can take them
over with a small accessibility service, `InputKeyService`, which also handles the Input button.

## Turn it on

`INPUT_KEY_SERVICE=1 launcher/build-and-install.sh` enables the service. Android drops accessibility services on
reinstall, so pass the flag every time.

## Find a button's number

Press the button with the service on, then:

```
tools/tv.py "logcat -d -s TvHome.Keys:I"
```

Buttons the system has a name for are not logged. For the others you get a line like `key 4065`. That number is the
Android key code.

## Map it to an app

`key_apps` in the config overlay, one entry per button, `"key|android.package|link"`:

```json
"key_apps": [
  "4065|com.google.android.youtube.tv",
  "4062|com.netflix.ninja",
  "4020|com.tcl.tv.tclchannel|action:com.tcl.waterfall.overseas.action.main"
]
```

- `key` is a number or a key name such as `KEYCODE_PROG_RED`.
- `link` is optional. With a link the app is asked to open it; without one, or if the app does not take it, the app
  opens normally.
- `action:name` starts an app whose start screen has no launcher entry and answers only to its own action. To find the
  name, look at the package's activities with `dumpsys package <package>`.
- The key numbers belong to the remote and TV model, not to the app.

Push the config with `tools/theme.sh <folder>`, then restart the service so it reads it:
`settings delete secure enabled_accessibility_services`, wait a moment, then put it back
(`com.gathin.tvhome/com.gathin.tvhome.InputKeyService`). Running the build script with the flag does the same.

## If a button still does nothing

`dumpsys window policy` shows which app handles each global key. A button sent to a package you disabled needs that
package enabled (`pm enable <package>`).
