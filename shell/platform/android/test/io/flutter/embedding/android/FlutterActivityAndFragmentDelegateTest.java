package io.flutter.embedding.android;

import android.app.Activity;
import android.arch.lifecycle.Lifecycle;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterShellArgs;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.plugins.activity.ActivityControlSurface;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import io.flutter.embedding.engine.systemchannels.AccessibilityChannel;
import io.flutter.embedding.engine.systemchannels.LifecycleChannel;
import io.flutter.embedding.engine.systemchannels.LocalizationChannel;
import io.flutter.embedding.engine.systemchannels.NavigationChannel;
import io.flutter.embedding.engine.systemchannels.SettingsChannel;
import io.flutter.embedding.engine.systemchannels.SystemChannel;
import io.flutter.plugin.platform.PlatformPlugin;
import io.flutter.plugin.platform.PlatformViewsController;
import io.flutter.view.FlutterMain;

import static android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Config(manifest=Config.NONE)
@RunWith(RobolectricTestRunner.class)
public class FlutterActivityAndFragmentDelegateTest {
  private FlutterEngine mockFlutterEngine;
  private FakeHost fakeHost;
  private FakeHost spyHost;

  @Before
  public void setup() {
    // FlutterMain is utilized statically, therefore we need to inform it to behave differently
    // for testing purposes.
    FlutterMain.setIsRunningInRobolectricTest(true);

    // Create a mocked FlutterEngine for the various interactions required by the delegate
    // being tested.
    mockFlutterEngine = mockFlutterEngine();

    // Create a fake Host, which is required by the delegate being tested.
    fakeHost = new FakeHost();
    fakeHost.flutterEngine = mockFlutterEngine;

    // Create a spy around the FakeHost so that we can verify method invocations.
    spyHost = spy(fakeHost);
  }

  @After
  public void teardown() {
    // Return FlutterMain to normal.
    FlutterMain.setIsRunningInRobolectricTest(false);
  }

  @Test
  public void itSendsLifecycleEventsToFlutter() {
    // ---- Test setup ----
    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(fakeHost);

    // We're testing lifecycle behaviors, which require/expect that certain methods have already
    // been executed by the time they run. Therefore, we run those expected methods first.
    delegate.onAttach(RuntimeEnvironment.application);
    delegate.onCreateView(null, null, null);

    // --- Execute the behavior under test ---
    // By the time an Activity/Fragment is started, we don't expect any lifecycle messages
    // to have been sent to Flutter.
    delegate.onStart();
    verify(mockFlutterEngine.getLifecycleChannel(), never()).appIsResumed();
    verify(mockFlutterEngine.getLifecycleChannel(), never()).appIsPaused();
    verify(mockFlutterEngine.getLifecycleChannel(), never()).appIsInactive();

    // When the Activity/Fragment is resumed, a resumed message should have been sent to Flutter.
    delegate.onResume();
    verify(mockFlutterEngine.getLifecycleChannel(), times(1)).appIsResumed();
    verify(mockFlutterEngine.getLifecycleChannel(), never()).appIsInactive();
    verify(mockFlutterEngine.getLifecycleChannel(), never()).appIsPaused();

    // When the Activity/Fragment is paused, an inactive message should have been sent to Flutter.
    delegate.onPause();
    verify(mockFlutterEngine.getLifecycleChannel(), times(1)).appIsResumed();
    verify(mockFlutterEngine.getLifecycleChannel(), times(1)).appIsInactive();
    verify(mockFlutterEngine.getLifecycleChannel(), never()).appIsPaused();

    // When the Activity/Fragment is stopped, a paused message should have been sent to Flutter.
    // Notice that Flutter uses the term "paused" in a different way, and at a different time
    // than the Android OS.
    delegate.onStop();
    verify(mockFlutterEngine.getLifecycleChannel(), times(1)).appIsResumed();
    verify(mockFlutterEngine.getLifecycleChannel(), times(1)).appIsInactive();
    verify(mockFlutterEngine.getLifecycleChannel(), times(1)).appIsPaused();
  }

  @Test
  public void itDefersToTheHostToProvideFlutterEngine() {
    // ---- Test setup ----
    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // The FlutterEngine is created in onAttach().
    delegate.onAttach(RuntimeEnvironment.application);

    // Verify that the host was asked to provide a FlutterEngine.
    verify(spyHost, times(1)).provideFlutterEngine(any(Context.class));

    // Verify that the delegate's FlutterEngine is our mock FlutterEngine.
    assertEquals("The delegate failed to use the host's FlutterEngine.", mockFlutterEngine, delegate.getFlutterEngine());
  }

  @Test
  public void itGivesHostAnOpportunityToConfigureFlutterEngine() {
    // ---- Test setup ----
    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // The FlutterEngine is created in onAttach().
    delegate.onAttach(RuntimeEnvironment.application);

    // Verify that the host was asked to configure our FlutterEngine.
    verify(spyHost, times(1)).configureFlutterEngine(mockFlutterEngine);
  }

  @Test
  public void itSendsInitialRouteToFlutter() {
    // ---- Test setup ----
    // Set initial route on our fake Host.
    spyHost.initialRoute = "/my/route";

    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // The initial route is sent in onStart().
    delegate.onAttach(RuntimeEnvironment.application);
    delegate.onCreateView(null, null, null);
    delegate.onStart();

    // Verify that the navigation channel was given our initial route.
    verify(mockFlutterEngine.getNavigationChannel(), times(1)).setInitialRoute("/my/route");
  }

  @Test
  public void itExecutesDartEntrypointProvidedByHost() {
    // ---- Test setup ----
    // Set Dart entrypoint parameters on fake host.
    spyHost.appBundlePath = "/my/bundle/path";
    spyHost.dartEntrypointFunctionName = "myEntrypoint";

    // Create the DartEntrypoint that we expect to be executed.
    DartExecutor.DartEntrypoint dartEntrypoint = new DartExecutor.DartEntrypoint(
        "/my/bundle/path",
        "myEntrypoint"
    );

    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // Dart is executed in onStart().
    delegate.onAttach(RuntimeEnvironment.application);
    delegate.onCreateView(null, null, null);
    delegate.onStart();

    // Verify that the host's Dart entrypoint was used.
    verify(mockFlutterEngine.getDartExecutor(), times(1)).executeDartEntrypoint(eq(dartEntrypoint));
  }

  // "Attaching" to the surrounding Activity refers to Flutter being able to control
  // system chrome and other Activity-level details. If Flutter is not attached to the
  // surrounding Activity, it cannot control those details. This includes plugins.
  @Test
  public void itAttachesFlutterToTheActivityIfDesired() {
    // ---- Test setup ----
    // Declare that the host wants Flutter to attach to the surrounding Activity.
    spyHost.shouldAttachToActivity = true;

    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // Flutter is attached to the surrounding Activity in onAttach.
    delegate.onAttach(RuntimeEnvironment.application);

    // Verify that the ActivityControlSurface was told to attach to an Activity.
    verify(mockFlutterEngine.getActivityControlSurface(), times(1)).attachToActivity(any(Activity.class), any(Lifecycle.class));

    // Flutter is detached from the surrounding Activity in onDetach.
    delegate.onDetach();

    // Verify that the ActivityControlSurface was told to detach from the Activity.
    verify(mockFlutterEngine.getActivityControlSurface(), times(1)).detachFromActivity();
  }

  // "Attaching" to the surrounding Activity refers to Flutter being able to control
  // system chrome and other Activity-level details. If Flutter is not attached to the
  // surrounding Activity, it cannot control those details. This includes plugins.
  @Test
  public void itDoesNotAttachFlutterToTheActivityIfNotDesired() {
    // ---- Test setup ----
    // Declare that the host does NOT want Flutter to attach to the surrounding Activity.
    spyHost.shouldAttachToActivity = false;

    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // Flutter is attached to the surrounding Activity in onAttach.
    delegate.onAttach(RuntimeEnvironment.application);

    // Verify that the ActivityControlSurface was NOT told to attach to an Activity.
    verify(mockFlutterEngine.getActivityControlSurface(), never()).attachToActivity(any(Activity.class), any(Lifecycle.class));

    // Flutter is detached from the surrounding Activity in onDetach.
    delegate.onDetach();

    // Verify that the ActivityControlSurface was NOT told to detach from the Activity.
    verify(mockFlutterEngine.getActivityControlSurface(), never()).detachFromActivity();
  }

  @Test
  public void itSendsPopRouteMessageToFlutterWhenHardwareBackButtonIsPressed() {
    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // The FlutterEngine is setup in onAttach().
    delegate.onAttach(RuntimeEnvironment.application);

    // Emulate the host and inform our delegate that the back button was pressed.
    delegate.onBackPressed();

    // Verify that the navigation channel tried to send a message to Flutter.
    verify(mockFlutterEngine.getNavigationChannel(), times(1)).popRoute();
  }

  @Test
  public void itForwardsOnRequestPermissionsResultToFlutterEngine() {
    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // The FlutterEngine is setup in onAttach().
    delegate.onAttach(RuntimeEnvironment.application);

    // Emulate the host and call the method that we expect to be forwarded.
    delegate.onRequestPermissionsResult(0, new String[]{}, new int[]{});

    // Verify that the call was forwarded to the engine.
    verify(mockFlutterEngine.getActivityControlSurface(), times(1)).onRequestPermissionsResult(any(Integer.class), any(String[].class), any(int[].class));
  }

  @Test
  public void itForwardsOnNewIntentToFlutterEngine() {
    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // The FlutterEngine is setup in onAttach().
    delegate.onAttach(RuntimeEnvironment.application);

    // Emulate the host and call the method that we expect to be forwarded.
    delegate.onNewIntent(mock(Intent.class));

    // Verify that the call was forwarded to the engine.
    verify(mockFlutterEngine.getActivityControlSurface(), times(1)).onNewIntent(any(Intent.class));
  }

  @Test
  public void itForwardsOnActivityResultToFlutterEngine() {
    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // The FlutterEngine is setup in onAttach().
    delegate.onAttach(RuntimeEnvironment.application);

    // Emulate the host and call the method that we expect to be forwarded.
    delegate.onActivityResult(0, 0, null);

    // Verify that the call was forwarded to the engine.
    verify(mockFlutterEngine.getActivityControlSurface(), times(1)).onActivityResult(any(Integer.class), any(Integer.class), any(Intent.class));
  }

  @Test
  public void itForwardsOnUserLeaveHintToFlutterEngine() {
    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // The FlutterEngine is setup in onAttach().
    delegate.onAttach(RuntimeEnvironment.application);

    // Emulate the host and call the method that we expect to be forwarded.
    delegate.onUserLeaveHint();

    // Verify that the call was forwarded to the engine.
    verify(mockFlutterEngine.getActivityControlSurface(), times(1)).onUserLeaveHint();
  }

  @Test
  public void itSendsMessageOverSystemChannelWhenToldToTrimMemory() {
    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // The FlutterEngine is setup in onAttach().
    delegate.onAttach(RuntimeEnvironment.application);

    // Emulate the host and call the method that we expect to be forwarded.
    delegate.onTrimMemory(TRIM_MEMORY_RUNNING_LOW);

    // Verify that the call was forwarded to the engine.
    verify(mockFlutterEngine.getSystemChannel(), times(1)).sendMemoryPressureWarning();
  }

  @Test
  public void itSendsMessageOverSystemChannelWhenInformedOfLowMemory() {
    // Create the real object that we're testing.
    FlutterActivityAndFragmentDelegate delegate = new FlutterActivityAndFragmentDelegate(spyHost);

    // --- Execute the behavior under test ---
    // The FlutterEngine is setup in onAttach().
    delegate.onAttach(RuntimeEnvironment.application);

    // Emulate the host and call the method that we expect to be forwarded.
    delegate.onLowMemory();

    // Verify that the call was forwarded to the engine.
    verify(mockFlutterEngine.getSystemChannel(), times(1)).sendMemoryPressureWarning();
  }

  /**
   * Creates a mock {@link FlutterEngine}.
   * <p>
   * The heuristic for deciding what to mock in the given {@link FlutterEngine} is that we
   * should mock the minimum number of necessary methods and associated objects. Maintaining
   * developers should add more mock behavior as required for tests, but should avoid mocking
   * things that are not required for the correct execution of tests.
   */
  @NonNull
  private FlutterEngine mockFlutterEngine() {
    // The use of SettingsChannel by the delegate requires some behavior of its own, so it is
    // explicitly mocked with some internal behavior.
    SettingsChannel fakeSettingsChannel = mock(SettingsChannel.class);
    SettingsChannel.MessageBuilder fakeMessageBuilder = mock(SettingsChannel.MessageBuilder.class);
    when(fakeMessageBuilder.setPlatformBrightness(any(SettingsChannel.PlatformBrightness.class))).thenReturn(fakeMessageBuilder);
    when(fakeMessageBuilder.setTextScaleFactor(any(Float.class))).thenReturn(fakeMessageBuilder);
    when(fakeMessageBuilder.setUse24HourFormat(any(Boolean.class))).thenReturn(fakeMessageBuilder);
    when(fakeSettingsChannel.startMessage()).thenReturn(fakeMessageBuilder);

    // Mock FlutterEngine and all of its required direct calls.
    FlutterEngine engine = mock(FlutterEngine.class);
    when(engine.getDartExecutor()).thenReturn(mock(DartExecutor.class));
    when(engine.getRenderer()).thenReturn(mock(FlutterRenderer.class));
    when(engine.getPlatformViewsController()).thenReturn(mock(PlatformViewsController.class));
    when(engine.getAccessibilityChannel()).thenReturn(mock(AccessibilityChannel.class));
    when(engine.getSettingsChannel()).thenReturn(fakeSettingsChannel);
    when(engine.getLocalizationChannel()).thenReturn(mock(LocalizationChannel.class));
    when(engine.getLifecycleChannel()).thenReturn(mock(LifecycleChannel.class));
    when(engine.getNavigationChannel()).thenReturn(mock(NavigationChannel.class));
    when(engine.getSystemChannel()).thenReturn(mock(SystemChannel.class));
    when(engine.getActivityControlSurface()).thenReturn(mock(ActivityControlSurface.class));

    return engine;
  }

  /**
   * A {@link FlutterActivityAndFragmentDelegate.Host} that returns values desired by this
   * test suite.
   * <p>
   * Sane defaults are set for all properties. Tests in this suite can alter {@code FakeHost}
   * properties as needed for each test.
   */
  private static class FakeHost implements FlutterActivityAndFragmentDelegate.Host {
    private FlutterEngine flutterEngine;
    private String initialRoute = null;
    private String appBundlePath = "fake/path/";
    private String dartEntrypointFunctionName = "main";
    private Activity activity;
    private boolean shouldAttachToActivity = false;
    private boolean retainFlutterEngine = false;

    @NonNull
    @Override
    public Context getContext() {
      return RuntimeEnvironment.application;
    }

    @Nullable
    @Override
    public Activity getActivity() {
      if (activity == null) {
        // We must provide a real (or close to real) Activity because it is passed to
        // the FlutterView that the delegate instantiates.
        activity = Robolectric.setupActivity(Activity.class);
      }

      return activity;
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
      return mock(Lifecycle.class);
    }

    @NonNull
    @Override
    public FlutterShellArgs getFlutterShellArgs() {
      return new FlutterShellArgs(new String[]{});
    }

    @NonNull
    @Override
    public String getDartEntrypointFunctionName() {
      return dartEntrypointFunctionName;
    }

    @NonNull
    @Override
    public String getAppBundlePath() {
      return appBundlePath;
    }

    @Nullable
    @Override
    public String getInitialRoute() {
      return initialRoute;
    }

    @NonNull
    @Override
    public FlutterView.RenderMode getRenderMode() {
      return FlutterView.RenderMode.surface;
    }

    @NonNull
    @Override
    public FlutterView.TransparencyMode getTransparencyMode() {
      return FlutterView.TransparencyMode.opaque;
    }

    @Nullable
    @Override
    public SplashScreen provideSplashScreen() {
      return null;
    }

    @Nullable
    @Override
    public FlutterEngine provideFlutterEngine(@NonNull Context context) {
      return flutterEngine;
    }

    @Nullable
    @Override
    public PlatformPlugin providePlatformPlugin(@Nullable Activity activity, @NonNull FlutterEngine flutterEngine) {
      return null;
    }

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {}

    @Override
    public boolean shouldAttachEngineToActivity() {
      return shouldAttachToActivity;
    }

    @Override
    public boolean retainFlutterEngineAfterHostDestruction() {
      return retainFlutterEngine;
    }

    @Override
    public void onFirstFrameRendered() {}
  }
}
