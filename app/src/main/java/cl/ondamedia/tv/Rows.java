package cl.ondamedia.tv;

import android.content.Context;
import android.graphics.Rect;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Filas de tarjetas estilo Netflix: una lista vertical de filas, cada una horizontal. */
final class Rows {

    private Rows() {
    }

    interface Listener {
        void onFocus(Api.Show show);

        void onClick(Api.Show show);

        /** La selección llegó cerca del final de la fila: cargar más si hay. */
        void onNearEnd(Api.Category row, Runnable inserted);
    }

    /**
     * Mantiene el elemento seleccionado alineado al comienzo (a la izquierda en una fila, arriba
     * en la lista de filas) en vez de esperar a que llegue al borde, como en las apps de TV.
     */
    static final class AlignedLayoutManager extends LinearLayoutManager {
        AlignedLayoutManager(Context c, int orientation) {
            super(c, orientation, false);
        }

        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                     @NonNull Rect rect, boolean immediate,
                                                     boolean focusedChildVisible) {
            int delta = getOrientation() == HORIZONTAL
                    ? child.getLeft() - parent.getPaddingLeft()
                    : child.getTop() - parent.getPaddingTop();
            if (delta == 0) return false;
            if (getOrientation() == HORIZONTAL) {
                if (immediate) parent.scrollBy(delta, 0); else parent.smoothScrollBy(delta, 0);
            } else {
                if (immediate) parent.scrollBy(0, delta); else parent.smoothScrollBy(0, delta);
            }
            return true;
        }
    }

    // ---------- Tarjetas ----------

    static final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView label;
        final ProgressBar progress;

        CardHolder(View v) {
            super(v);
            image = v.findViewById(R.id.image);
            label = v.findViewById(R.id.label);
            progress = v.findViewById(R.id.progress);
            v.setClipToOutline(true);
            v.setOnFocusChangeListener((view, has) -> {
                view.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                        .translationZ(has ? 12f : 0f).setDuration(140).start();
            });
        }
    }

    static final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private final Api.Category row;
        private final Listener listener;
        private final Progress progress;
        private final boolean alwaysLabel;

        CardAdapter(Api.Category row, Listener listener, Progress progress, boolean alwaysLabel) {
            this.row = row;
            this.listener = listener;
            this.progress = progress;
            this.alwaysLabel = alwaysLabel;
            setHasStableIds(false);
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            Api.Show s = row.shows.get(position);
            Ui.loadCard(h.image, s.landscape);
            boolean label = alwaysLabel || s.landscape == null;
            h.label.setVisibility(label ? View.VISIBLE : View.GONE);
            h.label.setText(s.title);
            float f = s.mediaId != null ? progress.fraction(s.mediaId) : 0;
            h.progress.setVisibility(f > 0.01f ? View.VISIBLE : View.GONE);
            h.progress.setProgress(Math.round(f * 1000));
            h.itemView.setOnClickListener(v -> listener.onClick(s));
            h.itemView.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                        .translationZ(has ? 12f : 0f).setDuration(140).start();
                if (has) listener.onFocus(s);
            });
            if (position >= row.shows.size() - 6 && row.hasMore() && !row.loading) {
                int before = row.shows.size();
                listener.onNearEnd(row, () -> notifyItemRangeInserted(before, row.shows.size() - before));
            }
        }

        @Override
        public int getItemCount() {
            return row.shows.size();
        }
    }

    // ---------- Filas ----------

    static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final RecyclerView list;
        Api.Category row;

        RowHolder(View v, RecyclerView.RecycledViewPool pool) {
            super(v);
            title = v.findViewById(R.id.row_title);
            list = v.findViewById(R.id.row_list);
            list.setLayoutManager(new AlignedLayoutManager(v.getContext(), RecyclerView.HORIZONTAL));
            list.setRecycledViewPool(pool);
            list.setHasFixedSize(true);
            list.setItemAnimator(null);
        }
    }

    static final class RowAdapter extends RecyclerView.Adapter<RowHolder> {
        private final List<Api.Category> rows;
        private final Listener listener;
        private final Progress progress;
        private final RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        private final Map<String, Parcelable> scroll = new HashMap<>();
        private boolean labels;

        RowAdapter(List<Api.Category> rows, Listener listener, Progress progress) {
            this.rows = rows;
            this.listener = listener;
            this.progress = progress;
            pool.setMaxRecycledViews(0, 40);
        }

        /** Muestra siempre el título sobre la imagen (para episodios, que no tienen logo). */
        RowAdapter withLabels() {
            labels = true;
            return this;
        }

        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RowHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_row, parent, false), pool);
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder h, int position) {
            Api.Category row = rows.get(position);
            h.row = row;
            h.title.setText(row.name);
            h.list.setAdapter(new CardAdapter(row, listener, progress, labels || "episodes".equals(row.id)));
            Parcelable state = scroll.get(row.id);
            RecyclerView.LayoutManager lm = h.list.getLayoutManager();
            if (state != null && lm != null) lm.onRestoreInstanceState(state);
            else h.list.scrollToPosition(0);
        }

        @Override
        public void onViewRecycled(@NonNull RowHolder h) {
            RecyclerView.LayoutManager lm = h.list.getLayoutManager();
            if (h.row != null && lm != null) scroll.put(h.row.id, lm.onSaveInstanceState());
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
