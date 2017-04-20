package vn.mbm.phimp.me.wordpress;

import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentManager.OnBackStackChangedListener;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.MenuItemCompat.OnActionExpandListener;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.SearchView.OnQueryTextListener;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.model.MediaModel;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.store.AccountStore;
import org.wordpress.android.fluxc.store.MediaStore;
import org.wordpress.android.fluxc.store.MediaStore.OnMediaChanged;
import org.wordpress.android.fluxc.store.MediaStore.OnMediaListFetched;
import org.wordpress.android.fluxc.store.MediaStore.OnMediaUploaded;
import org.wordpress.android.util.ActivityUtils;
import org.wordpress.android.util.DateTimeUtils;
import org.wordpress.android.util.MediaUtils;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.ToastUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import vn.mbm.phimp.me.MyApplication;
import vn.mbm.phimp.me.R;

/**
 * The main activity in which the user can browse their media.
 */
public class MediaBrowserActivity extends AppCompatActivity implements MediaGridFragment.MediaGridListener,
        MediaItemFragment.MediaItemFragmentCallback, OnQueryTextListener, OnActionExpandListener,
        MediaEditFragment.MediaEditFragmentCallback {
    private static final int MEDIA_PERMISSION_REQUEST_CODE = 1;

    private static final String SAVED_QUERY = "SAVED_QUERY";
    private static final String BUNDLE_MEDIA_CAPTURE_PATH = "mediaCapturePath";

    @Inject Dispatcher mDispatcher;
    @Inject MediaStore mMediaStore;
    @Inject AccountStore mAccountStore;

    private SiteModel mSite;

    private MediaGridFragment mMediaGridFragment;
    private MediaItemFragment mMediaItemFragment;
    private MediaEditFragment mMediaEditFragment;

    // Views
    private Toolbar mToolbar;
    private SearchView mSearchView;
    private MenuItem mSearchMenuItem;
    private MenuItem mLogoutMenuItem;
    private Menu mMenu;

    // Services
    private MediaDeleteService.MediaDeleteBinder mDeleteService;
    private boolean mDeleteServiceBound;

    private String mQuery;
    private String mMediaCapturePath;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ((MyApplication) getApplication()).component().inject(this);
        setContentView(R.layout.media_browser_activity);

        if (savedInstanceState == null) {
            mSite = (SiteModel) getIntent().getSerializableExtra(MyApplication.SITE);
        } else {
            mSite = (SiteModel) savedInstanceState.getSerializable(MyApplication.SITE);
        }

        mToolbar = (Toolbar) findViewById(R.id.toolbar_mediabrowser);
        setSupportActionBar(mToolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.media);
        }

        if (mSite == null) {
            ToastUtils.showToast(this, R.string.blog_not_found, ToastUtils.Duration.SHORT);
            finish();
            return;
        }

        FragmentManager fm = getFragmentManager();
        fm.addOnBackStackChangedListener(mOnBackStackChangedListener);

        mMediaGridFragment = (MediaGridFragment) fm.findFragmentById(R.id.mediaGridFragment);
        mMediaItemFragment = (MediaItemFragment) fm.findFragmentByTag(MediaItemFragment.TAG);
        mMediaEditFragment = (MediaEditFragment) fm.findFragmentByTag(MediaEditFragment.TAG);

        FragmentTransaction ft = fm.beginTransaction();
        if (mMediaItemFragment != null) {
            ft.hide(mMediaGridFragment);
        }
        if (mMediaEditFragment != null && !mMediaEditFragment.isInLayout()) {
            ft.hide(mMediaItemFragment);
        }
        ft.commitAllowingStateLoss();

//        setupAddMenuPopup();

        // if media was shared add it to the library
        handleSharedMedia();
    }

    @Override
    public void onStart() {
        super.onStart();
        registerReceiver(mReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        mDispatcher.register(this);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mSearchMenuItem != null) {
            String tempQuery = mQuery;
            MenuItemCompat.collapseActionView(mSearchMenuItem);
            mQuery = tempQuery;
        }
        if (mLogoutMenuItem != null) {
            String tempQuery = mQuery;
            MenuItemCompat.collapseActionView(mLogoutMenuItem);
            mQuery = tempQuery;
        }
    }

    @Override
    public void onPause(Fragment fragment) {
        invalidateOptionsMenu();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startMediaDeleteService(null);
    }

    @Override
    public void onResume(Fragment fragment) {
        invalidateOptionsMenu();
    }

    @Override
    public void onStop() {
        unregisterReceiver(mReceiver);
        mDispatcher.unregister(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        doUnbindDeleteService();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString(SAVED_QUERY, mQuery);
        outState.putSerializable(MyApplication.SITE, mSite);
        if (!TextUtils.isEmpty(mMediaCapturePath)) {
            outState.putString(BUNDLE_MEDIA_CAPTURE_PATH, mMediaCapturePath);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        mSite = (SiteModel) savedInstanceState.getSerializable(MyApplication.SITE);
        mMediaCapturePath = savedInstanceState.getString(BUNDLE_MEDIA_CAPTURE_PATH);
        mQuery = savedInstanceState.getString(SAVED_QUERY);
    }

    @Override
    public void onBackPressed() {
        FragmentManager fm = getFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            if (mMediaEditFragment != null && mMediaEditFragment.isVisible() && mMediaEditFragment.isDirty()) {
                // alert the user that there are unsaved changes
                new AlertDialog.Builder(this)
                        .setMessage(R.string.confirm_discard_changes)
                        .setCancelable(true)
                        .setPositiveButton(R.string.discard, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // make sure the keyboard is dismissed
                                InputMethodManager inputMethodManager = (InputMethodManager) getCurrentFocus().getContext()
                                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                                inputMethodManager.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);

                                // pop the edit fragment
                                doPopBackStack(getFragmentManager());
                            }})
                        .setNegativeButton(R.string.cancel, null)
                        .create()
                        .show();
            } else {
                doPopBackStack(fm);
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] results) {
        // only MEDIA_PERMISSION_REQUEST_CODE is handled
        if (requestCode != MEDIA_PERMISSION_REQUEST_CODE) {
            return;
        }

        for (int grantResult : results) {
            if (grantResult == PackageManager.PERMISSION_DENIED) {
                ToastUtils.showToast(this, getString(R.string.add_media_permission_required));
                return;
            }
        }

//        showNewMediaMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mMenu = menu;
        getMenuInflater().inflate(R.menu.media_browser, menu);

        mSearchMenuItem = menu.findItem(R.id.menu_search);
        mLogoutMenuItem = menu.findItem(R.id.menu_more_media);
        MenuItemCompat.setOnActionExpandListener(mSearchMenuItem, this);

        mSearchView = (SearchView) mSearchMenuItem.getActionView();
        mSearchView.setOnQueryTextListener(this);

        // open search bar if we were searching for something before
        if (!TextUtils.isEmpty(mQuery) && mMediaGridFragment != null && mMediaGridFragment.isVisible()) {
            String tempQuery = mQuery; //temporary hold onto query
            MenuItemCompat.expandActionView(mSearchMenuItem); //this will reset mQuery
            onQueryTextSubmit(tempQuery);
            mSearchView.setQuery(mQuery, true);
        }

        return super.onCreateOptionsMenu(menu);
    }

    private void logout()
    {
        if (mAccountStore.hasAccessToken()) {
            Logout logout = new Logout(this ,mAccountStore);
            logout.signOutWordPressComWithConfirmation();
        } else {
            Intent intent = new Intent(this, SignInActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            case R.id.menu_more_media:
                mLogoutMenuItem = item;
                logout();
                return true;
            case R.id.menu_search:
                mSearchMenuItem = item;
                MenuItemCompat.setOnActionExpandListener(mSearchMenuItem, this);
                MenuItemCompat.expandActionView(mSearchMenuItem);

                mSearchView = (SearchView) item.getActionView();
                mSearchView.setOnQueryTextListener(this);

                // load last saved query
                if (!TextUtils.isEmpty(mQuery)) {
                    onQueryTextSubmit(mQuery);
                    mSearchView.setQuery(mQuery, true);
                }
                return true;
            case R.id.menu_edit_media:
                int localMediaId = mMediaItemFragment.getLocalMediaId();

                if (mMediaEditFragment == null || !mMediaEditFragment.isInLayout()) {
                    // phone layout: hide item details, show and update edit fragment
                    FragmentManager fm = getFragmentManager();
                    mMediaEditFragment = MediaEditFragment.newInstance(mSite, localMediaId);

                    FragmentTransaction ft = fm.beginTransaction();
                    if (mMediaItemFragment.isVisible()) {
                        ft.hide(mMediaItemFragment);
                    }
                    ft.add(R.id.media_browser_container, mMediaEditFragment, MediaEditFragment.TAG);
                    ft.addToBackStack(null);
                    ft.commitAllowingStateLoss();
                } else {
                    // tablet layout: update edit fragment
                    mMediaEditFragment.loadMedia(localMediaId);
                }

                if (mSearchView != null) {
                    mSearchView.clearFocus();
                }

                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        if (mMediaGridFragment != null) {
            mMediaGridFragment.search(query);
        }

        mQuery = query;
        mSearchView.clearFocus();

        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        if (mMediaGridFragment != null) {
            mMediaGridFragment.search(newText);
        }

        mQuery = newText;

        return true;
    }

    @Override
    public void setLookClosable() {
        mToolbar.setNavigationIcon(R.drawable.ic_cross_white_24dp);
    }

    @Override
    public void onMediaItemSelected(int localMediaId) {
        final String tempQuery = mQuery;

        if (mSearchView != null) {
            mSearchView.clearFocus();
        }

        if (mSearchMenuItem != null) {
            MenuItemCompat.collapseActionView(mSearchMenuItem);
        }

        FragmentManager fm = getFragmentManager();
        if (fm.getBackStackEntryCount() == 0) {
            mMediaGridFragment.clearSelectedItems();
            mMediaItemFragment = MediaItemFragment.newInstance(mSite, localMediaId);

            FragmentTransaction ft = fm.beginTransaction();
            ft.hide(mMediaGridFragment);
            ft.add(R.id.media_browser_container, mMediaItemFragment, MediaItemFragment.TAG);
            ft.addToBackStack(null);
            ft.commitAllowingStateLoss();

            mQuery = tempQuery;
        }
    }

    @Override
    public void onRetryUpload(int localMediaId) {
        MediaModel media = mMediaStore.getMediaWithLocalId(localMediaId);
        if (media == null) {
            return;
        }
        addMediaToUploadService(media);
    }

    private void showMediaToastError(@StringRes int message, @Nullable String messageDetail) {
        if (isFinishing()) {
            return;
        }
        String errorMessage = getString(message);
        if (!TextUtils.isEmpty(messageDetail)) {
            errorMessage += ". " + messageDetail;
        }
        ToastUtils.showToast(this, errorMessage, ToastUtils.Duration.LONG);
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMediaChanged(OnMediaChanged event) {
        if (event.isError()) {
            showMediaToastError(R.string.media_generic_error, event.error.message);
            return;
        }

        switch (event.cause) {
            case DELETE_MEDIA:
                if (event.mediaList == null || event.mediaList.isEmpty()) {
                    break;
                }

                // If the media was deleted, remove it from multi select (if it was selected) and hide it from the
                // detail view (if it was the one displayed)
                for (MediaModel mediaModel : event.mediaList) {
                    int localMediaId = mediaModel.getId();
                    mMediaGridFragment.removeFromMultiSelect(localMediaId);
                    if (mMediaEditFragment != null && mMediaEditFragment.isVisible()
                            && localMediaId == mMediaEditFragment.getLocalMediaId()) {
                        updateOnMediaChanged(localMediaId);
                        if (mMediaEditFragment.isInLayout()) {
                            mMediaEditFragment.loadMedia(MediaEditFragment.MISSING_MEDIA_ID);
                        } else {
                            getFragmentManager().popBackStack();
                        }
                    }
                }
                break;
        }
        updateViews();
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMediaListFetched(OnMediaListFetched event) {
        if (event.isError()) {
            ToastUtils.showToast(this, "Media fetch error occurred: " + event.error.message, ToastUtils.Duration.LONG);
            return;
        }
        updateViews();
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMediaUploaded(OnMediaUploaded event) {
        if (event.isError()) {
            switch (event.error.type) {
                case AUTHORIZATION_REQUIRED:
                    showMediaToastError(R.string.media_error_no_permission, null);
                    break;
                case REQUEST_TOO_LARGE:
                    showMediaToastError(R.string.media_error_too_large_upload, null);
                    break;
                default:
                    showMediaToastError(R.string.media_upload_error, event.error.message);
            }
            updateViews();
        } else if (event.completed) {
            String title = "";
            if (event.media != null) {
                title = event.media.getTitle();
            }
            Log.d("title", title);
            updateViews();
        }
    }

    public void onSavedEdit(int localMediaId, boolean result) {
        if (mMediaEditFragment != null && mMediaEditFragment.isVisible() && result) {
            doPopBackStack(getFragmentManager());

            // refresh media item details (phone-only)
            if (mMediaItemFragment != null)
                mMediaItemFragment.loadMedia(localMediaId);

            // refresh grid
            mMediaGridFragment.refreshMediaFromDB();
        }
    }

    private void updateOnMediaChanged(int localMediaId) {
        if (localMediaId == -1) {
            return;
        }

        // If the media was deleted, remove it from multi select (if it was selected) and hide it from the the detail
        // view (if it was the one displayed)
        if (mMediaStore.getMediaWithLocalId(localMediaId) == null) {
            mMediaGridFragment.removeFromMultiSelect(localMediaId);
            if (mMediaEditFragment != null && mMediaEditFragment.isVisible()
                    && localMediaId == mMediaEditFragment.getLocalMediaId()) {
                if (mMediaEditFragment.isInLayout()) {
                    mMediaEditFragment.loadMedia(MediaEditFragment.MISSING_MEDIA_ID);
                } else {
                    doPopBackStack(getFragmentManager());
                }
            }
        }
        updateViews();
    }

    public void deleteMedia(final ArrayList<Integer> ids) {
        Set<String> sanitizedIds = new HashSet<>(ids.size());

        // phone layout: pop the item fragment if it's visible
        doPopBackStack(getFragmentManager());

        final ArrayList<MediaModel> mediaToDelete = new ArrayList<>();
        // Make sure there are no media in "uploading"
        for (int currentId : ids) {
            MediaModel mediaModel = mMediaStore.getMediaWithLocalId(currentId);
            if (mediaModel == null) {
                continue;
            }

            if (WordPressMediaUtils.canDeleteMedia(mediaModel)) {
                if (mediaModel.getUploadState() != null &&
                        MediaUtils.isLocalFile(mediaModel.getUploadState().toLowerCase())) {
                    mDispatcher.dispatch(MediaActionBuilder.newRemoveMediaAction(mediaModel));
                    sanitizedIds.add(String.valueOf(currentId));
                    continue;
                }
                mediaToDelete.add(mediaModel);
                mediaModel.setUploadState(MediaUploadState.DELETING.name());
                mDispatcher.dispatch(MediaActionBuilder.newUpdateMediaAction(mediaModel));
                sanitizedIds.add(String.valueOf(currentId));
            }
        }

        if (sanitizedIds.size() != ids.size()) {
            if (ids.size() == 1) {
                Toast.makeText(this, R.string.wait_until_upload_completes, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.cannot_delete_multi_media_items, Toast.LENGTH_LONG).show();
            }
        }

        // mark items for delete without actually deleting items yet,
        // and then refresh the grid
        if (!mediaToDelete.isEmpty()) {
            startMediaDeleteService(mediaToDelete);
        }
        if (mMediaGridFragment != null) {
            mMediaGridFragment.clearSelectedItems();
        }
    }

    private void uploadList(List<Uri> uriList) {
        for (Uri uri : uriList) {
            fetchMedia(uri, getContentResolver().getType(uri));
        }
    }

    private final OnBackStackChangedListener mOnBackStackChangedListener = new OnBackStackChangedListener() {
        @Override
        public void onBackStackChanged() {
            FragmentManager manager = getFragmentManager();
            MediaGridFragment mediaGridFragment = (MediaGridFragment) manager.findFragmentById(R.id.mediaGridFragment);
            if (mediaGridFragment.isVisible()) {
                mediaGridFragment.refreshSpinnerAdapter();
            }
            ActivityUtils.hideKeyboard(MediaBrowserActivity.this);
        }
    };

    private void doBindDeleteService(Intent intent) {
        mDeleteServiceBound = bindService(intent, mDeleteConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_ABOVE_CLIENT);
    }

    private void doUnbindDeleteService() {
        if (mDeleteServiceBound) {
            unbindService(mDeleteConnection);
            mDeleteServiceBound = false;
        }
    }

    private final ServiceConnection mDeleteConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mDeleteService = (MediaDeleteService.MediaDeleteBinder) service;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mDeleteService = null;
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                // Coming from zero connection. Continue what's pending for delete
                if (mMediaStore.hasSiteMediaToDelete(mSite)) {
                    startMediaDeleteService(null);
                }
            }
        }
    };

    private void fetchMedia(Uri mediaUri, final String mimeType) {
        if (!MediaUtils.isInMediaStore(mediaUri)) {
            // Create an AsyncTask to download the file
            new AsyncTask<Uri, Integer, Uri>() {
                @Override
                protected Uri doInBackground(Uri... uris) {
                    Uri imageUri = uris[0];
                    return MediaUtils.downloadExternalMedia(MediaBrowserActivity.this, imageUri);
                }

                protected void onPostExecute(Uri uri) {
                    if (uri != null) {
                        queueFileForUpload(uri, mimeType);
                    } else {
                        Toast.makeText(MediaBrowserActivity.this, getString(R.string.error),
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, mediaUri);
        } else {
            queueFileForUpload(mediaUri, mimeType);
        }
    }

    private void addMediaToUploadService(@NonNull MediaModel media) {
        // Start the upload service if it's not started and fill the media queue
        if (!NetworkUtils.isNetworkAvailable(this)) {
            return;
        }

        ArrayList<MediaModel> mediaList = new ArrayList<>();
        mediaList.add(media);
        MediaUploadService.startService(this, mSite, mediaList);
    }

    private void queueFileForUpload(Uri uri, String mimeType) {
        // It is a regular local media file
        String path = getRealPathFromURI(uri);

        if (path == null || path.equals("")) {
            Toast.makeText(this, "Error opening file", Toast.LENGTH_SHORT).show();
            return;
        }

        File file = new File(path);
        if (!file.exists()) {
            return;
        }

        MediaModel media = mMediaStore.instantiateMediaModel();
        String filename = org.wordpress.android.fluxc.utils.MediaUtils.getFileName(path);
        String fileExtension = org.wordpress.android.fluxc.utils.MediaUtils.getExtension(path);

        // Try to get mime type if none was passed to this method
        if (mimeType == null) {
            mimeType = getContentResolver().getType(uri);
            if (mimeType == null) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension);
            }
            if (mimeType == null) {
                // Default to image jpeg
                mimeType = "image/jpeg";
            }
        }
        // If file extension is null, upload won't work on wordpress.com
        if (fileExtension == null) {
            fileExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            filename += "." + fileExtension;
        }

        media.setFileName(filename);
        media.setFilePath(path);
        media.setLocalSiteId(mSite.getId());
        media.setFileExtension(fileExtension);
        media.setMimeType(mimeType);
        media.setUploadState(MediaUploadState.QUEUED.name());
        media.setUploadDate(DateTimeUtils.iso8601UTCFromTimestamp(System.currentTimeMillis() / 1000));
        mDispatcher.dispatch(MediaActionBuilder.newUpdateMediaAction(media));
        addMediaToUploadService(media);
    }

    private void handleSharedMedia() {
        Intent intent = getIntent();

        final List<Uri> multi_stream;
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            multi_stream = intent.getParcelableArrayListExtra((Intent.EXTRA_STREAM));
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            multi_stream = new ArrayList<>();
            multi_stream.add((Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM));
        } else {
            multi_stream = null;
        }

        if (multi_stream != null) {
            uploadList(multi_stream);
        }

        // clear the intent's action, so that in case the user rotates, we don't re-upload the same files
        getIntent().setAction(null);
    }

    private void startMediaDeleteService(ArrayList<MediaModel> mediaToDelete) {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            return;
        }

        if (mDeleteService != null) {
            if (mediaToDelete != null && !mediaToDelete.isEmpty()) {
                for (MediaModel media : mediaToDelete) {
                    mDeleteService.addMediaToDeleteQueue(media);
                }
            }
        } else {
            Intent intent = new Intent(this, MediaDeleteService.class);
            intent.putExtra(MediaDeleteService.SITE_KEY, mSite);
            if (mediaToDelete != null) {
                intent.putExtra(MediaDeleteService.MEDIA_LIST_KEY, mediaToDelete);
                doBindDeleteService(intent);
            }
            startService(intent);
        }
    }

    private void doPopBackStack(FragmentManager fm) {
        fm.popBackStack();

        // reset the button to "back" as it may have been altered by a fragment
        mToolbar.setNavigationIcon(R.drawable.ic_arrow_left_white_24dp);
    }

    private String getRealPathFromURI(Uri uri) {
        String path;
        if ("content".equals(uri.getScheme())) {
            path = MediaUtils.getPath(this, uri);
        } else if ("file".equals(uri.getScheme())) {
            path = uri.getPath();
        } else {
            path = uri.toString();
        }
        return path;
    }

    private void updateViews() {
        mMediaGridFragment.refreshMediaFromDB();
        mMediaGridFragment.updateFilterText();
        mMediaGridFragment.updateSpinnerAdapter();
    }

    @Override
    public boolean onMenuItemActionExpand(MenuItem item) {
        return false;
    }

    @Override
    public boolean onMenuItemActionCollapse(MenuItem item) {
        return false;
    }
}