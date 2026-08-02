# Tasks: opt-in live monitor

## 1. Persist the monitor preference

- [x] 1.1 Add an off-by-default `liveMonitorEnabled` DataStore key and flow
- [x] 1.2 Expose the preference through `SettingsRepository`
- [x] 1.3 Update all SettingsRepository test fakes

## 2. Support idle service monitoring

- [x] 2.1 Add a monitoring-state notification to `PresenceNotifications`
- [x] 2.2 Make `PresenceService` retain its poll when monitor is enabled and Steam reports not-playing
- [x] 2.3 Stop an idle service when the preference turns off, without erasing an active session
- [x] 2.4 Restart an enabled monitor when the app next enters the foreground

## 3. Add the Settings control

- [x] 3.1 Add the setting to SettingsUiState and its stored-state combine
- [x] 3.2 Add a Live monitor card and toggle under Settings with notification, network, battery, and timeout disclosure
- [x] 3.3 Persist the toggle and start the service after the user enables it; disabling is observed by the service

## 4. Tests and verification

- [x] 4.1 Unit-test monitor preference storage/repository forwarding
- [x] 4.2 Unit-test the service state decision: monitor on retains idle state, monitor off stops it, active session survives toggle-off
- [x] 4.3 Run the Debug unit-test suite
- [x] 4.4 Verify the six device scenarios in the design *(verified on device by the user)*
