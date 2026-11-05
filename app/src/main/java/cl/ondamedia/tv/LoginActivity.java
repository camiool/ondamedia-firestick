package cl.ondamedia.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Inicio de sesión con la cuenta de Ondamedia. La contraseña solo se envía a la API de
 * Ondamedia; en el dispositivo se guarda únicamente el token de sesión que devuelve.
 */
public class LoginActivity extends Activity {

    /** Pantalla que se abre después de iniciar sesión (p. ej. el reproductor). */
    static final String EXTRA_NEXT = "next";

    private EditText email, password;
    private TextView error;
    private Button button;
    private View loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        email = findViewById(R.id.email);
        password = findViewById(R.id.password);
        error = findViewById(R.id.error);
        button = findViewById(R.id.btn_login);
        loading = findViewById(R.id.loading);
        password.setTypeface(Typeface.DEFAULT);  // si no, el campo de contraseña usa monoespaciada

        String last = Api.get(this).userEmail();
        if (!last.isEmpty()) email.setText(last);

        button.setOnClickListener(v -> submit());
        password.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                button.requestFocus();
                submit();
                return true;
            }
            return false;
        });
        (last.isEmpty() ? email : password).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemUi(this);
    }

    private void submit() {
        String e = email.getText().toString().trim();
        String p = password.getText().toString();
        if (e.isEmpty() || p.isEmpty()) {
            showError("Escribe tu correo y tu contraseña.");
            return;
        }
        error.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        button.setEnabled(false);
        Api.get(this).login(e, p, new Api.Callback<Void>() {
            @Override
            public void ok(Void result) {
                Toast.makeText(LoginActivity.this, "Sesión iniciada", Toast.LENGTH_SHORT).show();
                Intent next = getIntent().getParcelableExtra(EXTRA_NEXT);
                if (next != null) startActivity(next);
                finish();
            }

            @Override
            public void error(String message) {
                loading.setVisibility(View.GONE);
                button.setEnabled(true);
                password.setText("");
                showError(message);
                password.requestFocus();
            }
        });
    }

    private void showError(String msg) {
        error.setText(msg);
        error.setVisibility(View.VISIBLE);
    }
}
