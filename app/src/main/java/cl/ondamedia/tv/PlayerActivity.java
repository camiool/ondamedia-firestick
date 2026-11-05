package cl.ondamedia.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

/**
 * Reproductor nativo (ExoPlayer) a pantalla completa.
 *
 * Control remoto: OK o Play/Pausa pausa y reanuda; izquierda/derecha retroceden o adelantan
 * (manteniendo presionado, cada vez más rápido); arriba/abajo muestran la barra; Atrás sale.
 * Si el video no se puede reproducir de forma nativa, se abre el reproductor web de Mediastream.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private PlayerView view;
    private TextView titleView, seekHint;
    private View loading;
    private Progress progress;
    private Api.Playback playback;

    private String mediaId, title, showId, image;
    private boolean fellBack;

    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            saveProgress();
            handler.postDelayed(this, 10_000);
        }
    };
    private final Runnable hideHint = () -> seekHint.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        progress = new Progress(this);

        view = findViewById(R.id.player_view);
        titleView = findViewById(R.id.player_title);
        seekHint = findViewById(R.id.seek_hint);
        loading = findViewById(R.id.loading);

        Intent i = getIntent();
        mediaId = i.getStringExtra(Ui.EXTRA_MEDIA);
        title = i.getStringExtra(Ui.EXTRA_TITLE);
        showId = i.getStringExtra(Ui.EXTRA_SHOW);
        image = i.getStringExtra(Ui.EXTRA_IMAGE);
        titleView.setText(title);

        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility ->
                titleView.setVisibility(visibility));

        Api.get(this).playback(mediaId, new Api.Callback<Api.Playback>() {
            @Override
            public void ok(Api.Playback result) {
                playback = result;
                if (!isFinishing()) start();
            }

            @Override
            public void error(String message) {
                if (Api.ApiException.LOGIN_REQUIRED.equals(message)) {
                    // La sesión venció: pedimos iniciarla de nuevo y volvemos aquí.
                    startActivity(new Intent(PlayerActivity.this, LoginActivity.class)
                            .putExtra(LoginActivity.EXTRA_NEXT, getIntent()));
                } else {
                    Toast.makeText(PlayerActivity.this, message, Toast.LENGTH_LONG).show();
                }
                finish();
            }
        });
    }

    private void start() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("OndamediaTV/2.0 (Android; Fire TV)")
                .setAllowCrossProtocolRedirects(true);
        player = new ExoPlayer.Builder(this)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        view.setPlayer(player);
        MediaItem item = new MediaItem.Builder()
                .setUri(Uri.parse(playback.hlsUrl))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();
        player.setMediaSource(new HlsMediaSource.Factory(http).createMediaSource(item));
        long resume = progress.resumePosition(mediaId);
        if (resume > 0) {
            player.seekTo(resume);
            Toast.makeText(this, "Continuando donde quedaste", Toast.LENGTH_SHORT).show();
        }
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                loading.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) loading.setVisibility(View.GONE);
                if (state == Player.STATE_ENDED) {
                    saveProgress();
                    finish();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                fallBackToWebPlayer(error);
            }
        });
        player.setPlayWhenReady(true);
        player.prepare();
        handler.postDelayed(saver, 10_000);
    }

    /** Si ExoPlayer no puede (formato, DRM, token), usamos el reproductor web de Mediastream. */
    private void fallBackToWebPlayer(PlaybackException error) {
        if (fellBack || playback == null) return;
        fellBack = true;
        long pos = player != null ? player.getCurrentPosition() : 0;
        startActivity(new Intent(this, EmbedPlayerActivity.class)
                .putExtras(getIntent())
                .putExtra(EmbedPlayerActivity.EXTRA_URL, playback.embedUrl)
                .putExtra(EmbedPlayerActivity.EXTRA_ERROR, error.getErrorCodeName())
                .putExtra(EmbedPlayerActivity.EXTRA_POSITION, pos));
        finish();
    }

    // ---------- Teclas ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                saveProgress();
                finish();
            }
            return true;
        }
        if (player == null) return super.dispatchKeyEvent(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && e.getRepeatCount() == 0) {
                    player.setPlayWhenReady(!player.getPlayWhenReady());
                    view.showController();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down) player.setPlayWhenReady(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) {
                    player.setPlayWhenReady(false);
                    view.showController();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) seek(-1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) seek(1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
                if (down) view.showController();
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Salto de 10 s; si se mantiene presionado, de 30 s y luego de 60 s. */
    private void seek(int dir, int repeat) {
        long step = repeat < 6 ? 10_000 : repeat < 20 ? 30_000 : 60_000;
        long dur = player.getDuration();
        long target = player.getCurrentPosition() + dir * step;
        if (target < 0) target = 0;
        if (dur > 0 && target > dur - 1000) target = dur - 1000;
        player.seekTo(target);
        view.showController();
        seekHint.setText((dir > 0 ? "+" : "−") + (step / 1000) + " s");
        seekHint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, 700);
    }

    // ---------- Ciclo de vida ----------

    private void saveProgress() {
        if (player == null || showId == null) return;  // los tráileres no se guardan
        progress.save(mediaId, showId, title, image, player.getCurrentPosition(), player.getDuration());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemUi(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
