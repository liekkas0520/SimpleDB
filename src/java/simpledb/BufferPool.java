package simpledb;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * BufferPool manages the reading and writing of pages into memory from disk.
 * Access methods call into it to retrieve pages, and it fetches pages from the
 * appropriate location.
 * <p>
 * The BufferPool is also responsible for locking; when a transaction fetches a
 * page, BufferPool checks that the transaction has the appropriate locks to
 * read/write the page.
 *
 * @Threadsafe, all fields are final
 */
public class BufferPool {
    /** Bytes per page, including header. */
    private static final int DEFAULT_PAGE_SIZE = 4096;

    private static int pageSize = DEFAULT_PAGE_SIZE;

    private ConcurrentHashMap<PageId, Page> pages = new ConcurrentHashMap<>();

    private int numPages = 0;

    /**
     * Default number of pages passed to the constructor. This is used by other
     * classes. BufferPool should use the numPages argument to the constructor
     * instead.
     */
    public static final int DEFAULT_PAGES = 50;
    private LockManager lockManager;
    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        // some code goes here
        this.numPages = numPages;
        lockManager = new LockManager();
    }

    public static int getPageSize() {
        return pageSize;
    }

    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void setPageSize(int pageSize) {
        BufferPool.pageSize = pageSize;
    }

    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void resetPageSize() {
        BufferPool.pageSize = DEFAULT_PAGE_SIZE;
    }

    /**
     * Retrieve the specified page with the associated permissions. Will acquire a
     * lock and may block if that lock is held by another transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool. If it is present,
     * it should be returned. If it is not present, it should be added to the buffer
     * pool and returned. If there is insufficient space in the buffer pool, a page
     * should be evicted and the new page should be added in its place.
     *
     * @param tid  the ID of the transaction requesting the page
     * @param pid  the ID of the requested page
     * @param perm the requested permissions on the page
     */
    public Page getPage(TransactionId tid, PageId pid, Permissions perm)
            throws TransactionAbortedException, DbException {
        // some code goes here
        //try to get the lock
        boolean result = (perm == Permissions.READ_ONLY) ? lockManager.grantSLock(tid, pid)
                : lockManager.grantXLock(tid, pid);
        while (!result) {
            // check deadlock
            if (lockManager.deadlockOccurred(tid, pid)) {
                throw new TransactionAbortedException();
            }
            // sleep for a while
            try {
                Thread.sleep(LockManager.SLEEP_MILLIS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            // try to get the lock again
            result = (perm == Permissions.READ_ONLY) ? lockManager.grantSLock(tid, pid)
                    : lockManager.grantXLock(tid, pid);
        }
        Page page = this.pages.get(pid);
        if (page == null) {
            if (this.pages.size() == this.numPages) {
                this.evictPage();
            }
            page = Database.getCatalog().getDatabaseFile(pid.getTableId()).readPage(pid);
            this.pages.put(pid, page);
        }
        return page;
    }

    /**
     * Releases the lock on a page. Calling this is very risky, and may result in
     * wrong behavior. Think hard about who needs to call this and why, and why they
     * can run the risk of calling it.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param pid the ID of the page to unlock
     */
    public void releasePage(TransactionId tid, PageId pid) throws TransactionAbortedException {
        // some code goes here
        // not necessary for lab1|lab2
        if(!lockManager.unlock(tid, pid))
            throw new TransactionAbortedException();
    }

    /**
     * Release all locks associated with a given transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     */
    public void transactionComplete(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for lab1|lab2
        transactionComplete(tid, true);
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId p) {
        // some code goes here
        // not necessary for lab1|lab2
        return lockManager.getLockState(tid, p) != null;
    }

    /**
     * Commit or abort a given transaction; release all locks associated to the
     * transaction.
     *
     * @param tid    the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     */
    public void transactionComplete(TransactionId tid, boolean commit) throws IOException {
        // some code goes here
        // not necessary for lab1|lab2
        if (commit) {
            flushPages(tid);
        } else {
            Set<PageId> pageIds = pages.keySet();
            for (PageId pageId : pageIds) {
                Page page = pages.get(pageId);
                if (page.isDirty() != null && page.isDirty().equals(tid)) {
                    this.pages.replace(pageId, page);
                }
            }
        }
        lockManager.releaseTransactionLocks(tid);

    }


    /**
     * Add a tuple to the specified table on behalf of transaction tid. Will acquire
     * a write lock on the page the tuple is added to and any other pages that are
     * updated (Lock acquisition is not needed for lab2). May block if the lock(s)
     * cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling their
     * markDirty bit, and adds versions of any pages that have been dirtied to the
     * cache (replacing any existing versions of those pages) so that future
     * requests see up-to-date pages.
     *
     * @param tid     the transaction adding the tuple
     * @param tableId the table to add the tuple to
     * @param t       the tuple to add
     */
    public void insertTuple(TransactionId tid, int tableId, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
        DbFile dbFile = Database.getCatalog().getDatabaseFile(tableId);
        List<Page> pages = dbFile.insertTuple(tid, t);
        for (Page page : pages) {
            Database.getBufferPool().pages.put(page.getId(), page);
            page.markDirty(true, tid);
        }
    }

    /**
     * Remove the specified tuple from the buffer pool. Will acquire a write lock on
     * the page the tuple is removed from and any other pages that are updated. May
     * block if the lock(s) cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling their
     * markDirty bit, and adds versions of any pages that have been dirtied to the
     * cache (replacing any existing versions of those pages) so that future
     * requests see up-to-date pages.
     *
     * @param tid the transaction deleting the tuple.
     * @param t   the tuple to delete
     */
    public void deleteTuple(TransactionId tid, Tuple t) throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
        RecordId rid = t.getRecordId();
        DbFile dbFile = Database.getCatalog().getDatabaseFile(rid.getPageId().getTableId());
        List<Page> pages = dbFile.deleteTuple(tid, t);
        for (Page page : pages) {
            page.markDirty(true, tid);
        }
    }

    /**
     * Flush all dirty pages to disk. NB: Be careful using this routine -- it writes
     * dirty data to disk so will break simpledb if running in NO STEAL mode.
     */
    public synchronized void flushAllPages() throws IOException {
        // some code goes here
        // not necessary for lab1
        for (PageId pid : this.pages.keySet()) {
//            this.flushPage(pid);
            Page page = pages.get(pid);
            if (page.isDirty() != null) {
                flushPage(pid);
            }
        }

    }

    /**
     * Remove the specific page id from the buffer pool. Needed by the recovery
     * manager to ensure that the buffer pool doesn't keep a rolled back page in its
     * cache.
     *
     * Also used by B+ tree files to ensure that deleted pages are removed from the
     * cache so they can be reused safely
     */
    public synchronized void discardPage(PageId pid) {
        // some code goes here
        // not necessary for lab1
        // this.flushPage(pid);
        this.pages.remove(pid);
    }

    /**
     * Flushes a certain page to disk
     *
     * @param pid an ID indicating the page to flush
     */
    private synchronized void flushPage(PageId pid) throws IOException {
        // some code goes here
        // not necessary for lab1
        Page page = pages.get(pid);
        DbFile dbFile = Database.getCatalog().getDatabaseFile(pid.getTableId());
        dbFile.writePage(page);
        page.markDirty(false, null);
    }

    /**
     * Write all pages of the specified transaction to disk.
     */
    public synchronized void flushPages(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for lab1|lab2
        Iterator<PageId> it = pages.keySet().iterator();
        while (it.hasNext()) {
            PageId pid = it.next();
            Page p = pages.get(pid);
            if (p.isDirty() != null && p.isDirty().equals(tid)) {
                flushPage(pid);
                if (p.isDirty() == null) {
                    p.setBeforeImage();
                }
            }
        }
    }

    /**
     * Discards a page from the buffer pool. Flushes the page to disk to ensure
     * dirty pages are updated on disk.
     */
    private synchronized void evictPage() throws DbException {
        // some code goes here
        // not necessary for lab1
//        List<Page> cleanPages = pages.values().stream().filter(page -> page.isDirty() == null)
//                .collect(Collectors.toList());
//        if (cleanPages.isEmpty()) {
//            throw new DbException("all pages in buffer is dirty");
//        }
//        Page page = cleanPages.get(new Random().nextInt(cleanPages.size()));
//        PageId pid = page.getId();
//
//        this.discardPage(pid);
        PageId[] pageIds = pages.keySet().toArray(new PageId[0]);
        Random random = new Random();
        PageId pageId = null;
        do {
            pageId = pageIds[random.nextInt(pageIds.length)];
        } while (pages.get(pageId).isDirty() != null);
        if (pageId == null) {
            throw new DbException("All pages are dirty!");
        }
    }

}
