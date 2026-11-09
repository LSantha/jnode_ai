package org.jnode.fs.jfat;

import java.io.IOException;
import java.util.Iterator;
import org.jnode.fs.FSEntry;

public class FatEntriesIterator extends FatEntriesFactory implements Iterator<FSEntry> {

    private final FatTable fatTable;

    public FatEntriesIterator(FatTable fatTable, FatDirectory directory, boolean includeDeleted) {
        super(directory, includeDeleted);

        this.fatTable = fatTable;
    }

    public boolean hasNext() {
        try {
            return hasNextEntry();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public FSEntry next() {
        try {
            if (includeDeleted) {
                return createNextEntry();
            } else {
                return fatTable.look(createNextEntry());
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void remove() {
        throw new UnsupportedOperationException("remove");
    }
}
