package org.onosproject.fwd;

public class StoragePoc {
    private String id;
    private String name;

    public StoragePoc(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String toString() {
        return "id : "+id+" - name : "+name;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
