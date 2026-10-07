package dev.zfold.assistant;

import android.app.PendingIntent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class CircleTileService extends TileService {
  @Override public void onStartListening(){
    Tile tile=getQsTile();if(tile==null)return;
    tile.setLabel(getString(R.string.circle_ask_short));
    tile.setContentDescription(getString(R.string.circle_ask_long));
    tile.setState(Tile.STATE_INACTIVE);tile.updateTile();
  }
  @Override public void onClick(){
    PendingIntent pending=PendingIntent.getActivity(this,7,CircleEntryActivity.intent(this),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(pending);
    else startActivityAndCollapse(CircleEntryActivity.intent(this));
  }
}
