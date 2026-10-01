package app.organicmaps.car.screens;

import android.location.Location;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.LifecycleOwner;
import app.organicmaps.car.R;
import app.organicmaps.car.screens.search.SearchScreen;
import app.organicmaps.car.util.UiHelpers;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Map;
import app.organicmaps.sdk.OrganicMaps;
import app.organicmaps.sdk.car.renderer.Renderer;
import app.organicmaps.sdk.car.screens.BaseMapScreen;
import app.organicmaps.sdk.location.LocationListener;
import app.organicmaps.sdk.location.LocationState;

public class FreeDriveScreen extends BaseMapScreen implements LocationListener
{
  // Zoom level applied once the map follows and rotates with the car. Higher = closer.
  private static final int DRIVING_ZOOM_LEVEL = 17;
  // Gives up if follow-and-rotate cannot be reached (e.g. no heading while parked).
  private static final int MAX_MODE_SWITCH_ATTEMPTS = 15;
  // Mode switches are applied asynchronously by the render thread: wait before checking again.
  private static final long MODE_SWITCH_SETTLE_MS = 800;

  private boolean mDrivingViewApplied;
  private int mModeSwitchAttempts;
  private long mLastModeSwitchMs;

  public FreeDriveScreen(@NonNull CarContext carContext, @NonNull OrganicMaps organicMapsContext,
                         @NonNull Renderer surfaceRenderer)
  {
    super(carContext, organicMapsContext, surfaceRenderer);
  }

  @Override
  public void onResume(@NonNull LifecycleOwner owner)
  {
    super.onResume(owner);
    // Applied only once per screen, so a manual zoom or pan is kept when coming back from Settings.
    if (!mDrivingViewApplied)
      getLocationHelper().addListener(this);
  }

  @Override
  public void onPause(@NonNull LifecycleOwner owner)
  {
    super.onPause(owner);
    getLocationHelper().removeListener(this);
  }

  @Override
  public void onLocationUpdated(@NonNull Location location)
  {
    if (mDrivingViewApplied || !Map.isEngineCreated())
      return;

    final long nowMs = SystemClock.elapsedRealtime();
    if (nowMs - mLastModeSwitchMs < MODE_SWITCH_SETTLE_MS)
      return;

    switch (LocationState.getMode())
    {
    case LocationState.FOLLOW_AND_ROTATE ->
    {
      applyDrivingZoom();
      finishDrivingView();
    }
    case LocationState.NOT_FOLLOW, LocationState.FOLLOW ->
    {
      if (++mModeSwitchAttempts > MAX_MODE_SWITCH_ATTEMPTS)
      {
        finishDrivingView();
        return;
      }
      // NOT_FOLLOW -> FOLLOW -> FOLLOW_AND_ROTATE (the latter only once a heading is known).
      LocationState.nativeSwitchToNextMode();
      mLastModeSwitchMs = nowMs;
    }
    // Waiting for the first fix or location is turned off: don't interfere.
    default -> {}
    }
  }

  private void applyDrivingZoom()
  {
    final int currentZoom = Framework.nativeGetDrawScale();
    if (currentZoom <= 0 || currentZoom == DRIVING_ZOOM_LEVEL)
      return;
    // In follow modes the core recenters the scale focus on the position arrow, so (0, 0) is fine.
    Map.onScale(Math.pow(2, DRIVING_ZOOM_LEVEL - currentZoom), 0, 0, true);
  }

  private void finishDrivingView()
  {
    mDrivingViewApplied = true;
    getLocationHelper().removeListener(this);
  }

  @NonNull
  @Override
  protected Template onGetTemplateImpl()
  {
    final NavigationTemplate.Builder builder = new NavigationTemplate.Builder();
    builder.setMapActionStrip(
        UiHelpers.createMapActionStrip(getCarContext(), getSurfaceRenderer(), getLocationHelper()));
    builder.setActionStrip(createActionStrip());

    return builder.build();
  }

  @NonNull
  private ActionStrip createActionStrip()
  {
    final Action.Builder backActionBuilder = new Action.Builder();
    backActionBuilder.setIcon(
        new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), R.drawable.ic_menu)).build());
    backActionBuilder.setOnClickListener(this::finish);

    final Action.Builder searchActionBuilder = new Action.Builder();
    searchActionBuilder.setIcon(
        new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), R.drawable.ic_search)).build());
    searchActionBuilder.setOnClickListener(() -> {
      getScreenManager().popToRoot();
      getScreenManager().push(
          new SearchScreen.Builder(getCarContext(), getOrganicMapsContext(), getSurfaceRenderer()).build());
    });

    final Action.Builder categoriesActionBuilder = new Action.Builder();
    categoriesActionBuilder.setIcon(
        new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), R.drawable.ic_address)).build());
    categoriesActionBuilder.setOnClickListener(() -> {
      getScreenManager().popToRoot();
      getScreenManager().push(new CategoriesScreen(getCarContext(), getOrganicMapsContext(), getSurfaceRenderer()));
    });

    final ActionStrip.Builder builder = new ActionStrip.Builder();
    builder.addAction(searchActionBuilder.build());
    builder.addAction(categoriesActionBuilder.build());
    builder.addAction(UiHelpers.createSettingsAction(this, getSurfaceRenderer()));
    builder.addAction(backActionBuilder.build());
    return builder.build();
  }
}
