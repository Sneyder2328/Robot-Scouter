package com.supercilex.robotscouter.ui.scout;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.customtabs.CustomTabsIntent;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TabLayout;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.NavUtils;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.firebase.crash.FirebaseCrash;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.supercilex.robotscouter.R;
import com.supercilex.robotscouter.data.model.Team;
import com.supercilex.robotscouter.data.remote.TbaService;
import com.supercilex.robotscouter.ui.teamlist.TeamListActivity;
import com.supercilex.robotscouter.util.Constants;
import com.supercilex.robotscouter.util.FirebaseUtils;
import com.supercilex.robotscouter.util.TagUtils;

public class ScoutActivity extends AppCompatActivity {
    private Team mTeam;

    private DatabaseReference mTeamRef;
    private DatabaseReference mScoutRef;
    private ValueEventListener mTeamListener;
    private ChildEventListener mScoutListener;

    private Menu mMenu;

    public static Intent createIntent(Context context,
                                      @NonNull String teamNumber,
                                      @Nullable String key) {
        Intent intent = new Intent(context, ScoutActivity.class);
        intent.putExtra(Constants.INTENT_TEAM_NUMBER, teamNumber);
        intent.putExtra(Constants.INTENT_TEAM_KEY, key);

        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        }

        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_current_scout);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        ScoutPagerAdapter scoutPagerAdapter = new ScoutPagerAdapter(getSupportFragmentManager());

        // Set up the ViewPager with the sections adapter.
        ViewPager viewPager = (ViewPager) findViewById(R.id.container);
        viewPager.setAdapter(scoutPagerAdapter);

        TabLayout tabLayout = (TabLayout) findViewById(R.id.scouts);
        tabLayout.setupWithViewPager(viewPager);

        mTeam = new Team(getTeamKey(savedInstanceState), getTeamNumber());
        if (mTeam.getNumber() == null) return;
        getSupportActionBar().setTitle(mTeam.getNumber());

        updateScouts(scoutPagerAdapter);

        notifyUserOffline(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mTeamListener != null) {
            mTeamRef.removeEventListener(mTeamListener);
        }
        mScoutRef.removeEventListener(mScoutListener);
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putString(Constants.INTENT_TEAM_KEY, mTeam.getKey());
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_current_scout, menu);
        mMenu = menu;
        updateUi();

        menu.findItem(R.id.action_visit_tba_team_website)
                .setTitle(String.format(getString(R.string.menu_item_visit_team_website_on_tba),
                                        mTeam.getNumber()));
        menu.findItem(R.id.action_visit_team_website)
                .setTitle(String.format(getString(R.string.menu_item_visit_team_website),
                                        mTeam.getNumber()));

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_visit_tba_team_website:
                CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
                builder.setToolbarColor(ContextCompat.getColor(this, R.color.color_primary));
                builder.setShowTitle(true);
                CustomTabsIntent customTabsIntent = builder.build();
                customTabsIntent.launchUrl(this,
                                           Uri.parse("https://www.thebluealliance.com/team/"
                                                             + mTeam.getNumber()));
                break;
            case R.id.action_visit_team_website:
                CustomTabsIntent.Builder teamWebsiteBuilder = new CustomTabsIntent.Builder();
                teamWebsiteBuilder.setToolbarColor(ContextCompat.getColor(this,
                                                                          R.color.color_primary));
                teamWebsiteBuilder.setShowTitle(true);
                CustomTabsIntent teamWebsiteCustomTabsIntent = teamWebsiteBuilder.build();
                teamWebsiteCustomTabsIntent.launchUrl(this, Uri.parse(mTeam.getWebsite()));
                break;
            case R.id.action_edit_details:
                DialogFragment newFragment = EditDetailsDialogFragment.newInstance(mTeam.getNumber(),
                                                                                   mTeam.getKey(),
                                                                                   mTeam.getName(),
                                                                                   mTeam.getWebsite(),
                                                                                   mTeam.getMedia());
                newFragment.show(getSupportFragmentManager(), TagUtils.getTag(this));
                break;
            case R.id.action_settings:
                break;
            case android.R.id.home:
                if (NavUtils.shouldUpRecreateTask(
                        this, new Intent(this, TeamListActivity.class))) {
                    TaskStackBuilder.create(this).addParentStack(this).startActivities();
                    finish();
                } else {
                    NavUtils.navigateUpFromSameTask(this);
                }
                break;
        }

        return true;
    }

    private void updateUi() {
        if (mTeam.getKey() != null) {
            mTeamListener = new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    if (dataSnapshot.getValue() != null) {
                        mTeam = dataSnapshot.getValue(Team.class);

                        if (mTeam.getName() != null) {
                            String title = mTeam.getNumber() + " - " + mTeam.getName();

                            getSupportActionBar().setTitle(title);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                setTaskDescription(new ActivityManager.TaskDescription(title));
                            }
                        }

                        Glide.with(ScoutActivity.this)
                                .load(mTeam.getMedia())
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .error(R.drawable.ic_android_black_24dp)
                                .into((ImageView) findViewById(R.id.backdrop));

                        // TODO: 09/20/2016 Use tasks API for this to know when mMenu is ready
                        if (mTeam.getWebsite() != null) {
                            mMenu.findItem(R.id.action_visit_team_website).setVisible(true);
                        } else {
                            mMenu.findItem(R.id.action_visit_team_website).setVisible(false);
                        }
                    }
                }

                @Override
                public void onCancelled(DatabaseError databaseError) {
                    FirebaseCrash.report(databaseError.toException());
                }
            };

            mTeamRef = FirebaseUtils.getDatabase()
                    .getReference()
                    .child(Constants.FIREBASE_TEAMS)
                    .child(mTeam.getKey());

            mTeamRef.addValueEventListener(mTeamListener);
        } else {
            FirebaseUtils.getDatabase()
                    .getReference()
                    .child(Constants.FIREBASE_TEAM_INDEXES)
                    .child(FirebaseUtils.getUser().getUid())
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot dataSnapshot) {
                            if (dataSnapshot.getValue() != null) {
                                for (DataSnapshot child : dataSnapshot.getChildren()) {
                                    if (child.getValue().toString().equals(mTeam.getNumber())) {
                                        mTeam.setKey(child.getKey());
                                        updateUi();
                                        return;
                                    }
                                }
                            }

                            mTeam.addTeam();
                            new TbaService(mTeam.getNumber(), mTeam, ScoutActivity.this) {
                                @Override
                                public void onFinished(Team team, boolean isSuccess) {
                                    if (isSuccess) {
                                        team.addTeamData(mTeam.getKey());
                                        updateUi();
                                    } else {
                                        startDownloadTeamDataJob();
                                    }
                                }
                            };
                        }

                        @Override
                        public void onCancelled(DatabaseError databaseError) {
                            FirebaseCrash.report(databaseError.toException());
                        }
                    });
        }
    }

    private void startDownloadTeamDataJob() {
//        Driver myDriver = new GooglePlayDriver(this);
//        FirebaseJobDispatcher dispatcher = new FirebaseJobDispatcher(myDriver);
//
//        Bundle bundle = new Bundle();
//        bundle.putString(Constants.INTENT_TEAM_NUMBER, mNumber);
//        bundle.putString(Constants.INTENT_TEAM_KEY, mKey);
//
//        Job job = dispatcher.newJobBuilder()
//                .setService(DownloadTeamDataJob.class)
//                .setTag(mNumber)
//                .setReplaceCurrent(true)
//                .setConstraints(Constraint.ON_ANY_NETWORK)
//                .setTrigger(Trigger.NOW)
//                .setExtras(bundle)
//                .build();
//
//        int result = dispatcher.schedule(job);
//        if (result != FirebaseJobDispatcher.SCHEDULE_RESULT_SUCCESS) {
//            FirebaseCrash.report(new IllegalArgumentException("Job Scheduler failed."));
//        }
    }

    private void updateScouts(final ScoutPagerAdapter mScoutPagerAdapter) {
        mScoutListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                mScoutPagerAdapter.add(dataSnapshot.getKey());
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {

            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {
                mScoutPagerAdapter.remove(dataSnapshot.getKey());
            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {

            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                FirebaseCrash.report(databaseError.toException());
            }
        };

        mScoutRef = FirebaseUtils.getDatabase()
                .getReference()
                .child(Constants.FIREBASE_SCOUT_INDEXES)
                .child(FirebaseUtils.getUser().getUid())
                .child(mTeam.getNumber());

        mScoutRef.addChildEventListener(mScoutListener);
    }

    private String getTeamNumber() {
        String teamNumber = getIntent().getStringExtra(Constants.INTENT_TEAM_NUMBER);

        if (teamNumber != null) {
            return teamNumber;
        } else {
            FirebaseCrash.report(new IllegalStateException(
                    "Could not retrieve team number from intent"));
            finish();
            return null;
        }
    }

    private String getTeamKey(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            return savedInstanceState.getString(Constants.INTENT_TEAM_KEY);
        } else {
            return getIntent().getStringExtra(Constants.INTENT_TEAM_KEY);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();

        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private void notifyUserOffline(Bundle savedInstanceState) {
        if (savedInstanceState == null && !isNetworkAvailable()) {
            Snackbar.make(findViewById(android.R.id.content),
                          R.string.no_connection_current_scout,
                          Snackbar.LENGTH_SHORT).show();
        }
    }
}
