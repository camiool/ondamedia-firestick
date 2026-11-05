package cl.ondamedia.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Búsqueda: un campo de texto y una grilla de resultados. */
public class SearchActivity extends Activity implements Rows.Listener {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Api.Category results = new Api.Category("search", "Resultados", 0);

    private EditText query;
    private TextView status;
    private RecyclerView grid;
    private Rows.CardAdapter adapter;
    private Runnable pending;
    private String lastQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        query = findViewById(R.id.query);
        status = findViewById(R.id.status);
        grid = findViewById(R.id.results);
        grid.setLayoutManager(new GridLayoutManager(this, 4));
        grid.setItemAnimator(null);
        adapter = new Rows.CardAdapter(results, this, new Progress(this), true);
        grid.setAdapter(adapter);

        query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                schedule(600);
            }
        });
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                schedule(0);
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
                if (!results.shows.isEmpty()) grid.post(() -> grid.requestFocus());
                return true;
            }
            return false;
        });
        query.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemUi(this);
    }

    private void schedule(long delay) {
        if (pending != null) handler.removeCallbacks(pending);
        pending = this::search;
        handler.postDelayed(pending, delay);
    }

    private void search() {
        String q = query.getText().toString().trim();
        if (q.equals(lastQuery)) return;
        lastQuery = q;
        if (q.length() < 2) {
            results.shows.clear();
            adapter.notifyDataSetChanged();
            status.setText("Escribe al menos dos letras.");
            return;
        }
        status.setText("Buscando…");
        Api.get(this).search(q, new Api.Callback<List<Api.Show>>() {
            @Override
            public void ok(List<Api.Show> result) {
                if (!q.equals(lastQuery)) return;  // llegó una búsqueda más nueva
                results.shows.clear();
                results.shows.addAll(result);
                adapter.notifyDataSetChanged();
                status.setText(result.isEmpty() ? "No encontramos nada con «" + q + "»."
                        : result.size() + (result.size() == 1 ? " resultado" : " resultados"));
            }

            @Override
            public void error(String message) {
                status.setText(message);
            }
        });
    }

    @Override
    public void onFocus(Api.Show show) {
    }

    @Override
    public void onClick(Api.Show show) {
        startActivity(Ui.detailIntent(this, show));
    }

    @Override
    public void onNearEnd(Api.Category row, Runnable inserted) {
    }
}
