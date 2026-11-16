package android.content;

public class Intent {
    private String action;
    private String pkg;
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
    public String getPackage() { return pkg; }
}
