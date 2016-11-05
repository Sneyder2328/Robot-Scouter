package com.supercilex.robotscouter.ui.teamlist;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.firebase.ui.auth.AuthUI;
import com.firebase.ui.database.FirebaseIndexRecyclerAdapter;
import com.firebase.ui.database.FirebaseRecyclerAdapter;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.crash.FirebaseCrash;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.supercilex.robotscouter.R;
import com.supercilex.robotscouter.data.model.Team;
import com.supercilex.robotscouter.util.Constants;
import com.supercilex.robotscouter.util.FirebaseUtils;
import com.supercilex.robotscouter.util.TagUtils;

import java.util.Arrays;

import static com.firebase.ui.auth.ui.AcquireEmailHelper.RC_SIGN_IN;

// TODO: 08/10/2016 add Firebase analytics to menu item clicks so I know what stuff to put on top
// TODO: 08/10/2016 make users enter their team number to setup database with their team as example. Also add Firebase analytics to make sure this isn't getting rid of users.
// TODO: 08/31/2016 Look for FirebaseCrash.report() and set up FirebaseCrash.log(). log will put logs for the crash.

public class TeamListActivity extends AppCompatActivity {
    private static final String MANAGER_STATE = "manager_state";
    private static final String COUNT = "count";

    private FirebaseRecyclerAdapter mAdapter;
    private LinearLayoutManager mManager;
    private FirebaseAuth.AuthStateListener mAuthStateListener;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        setTheme(R.style.AppTheme_NoActionBar);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_team_list);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        findViewById(R.id.fab).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new NewTeamDialogFragment().show(getSupportFragmentManager(),
                                                 TagUtils.getTag(this));
            }
        });

        final RecyclerView teams = (RecyclerView) findViewById(R.id.team_list);
        teams.setHasFixedSize(true);
        mManager = new LinearLayoutManager(this);
        teams.setLayoutManager(mManager);
        // TODO: 09/03/2016 how to know when user is at bottom of RecyclerView for pagination
        mAdapter = new FirebaseIndexRecyclerAdapter<Team, TeamHolder>(
                Team.class,
                R.layout.activity_team_list_row_layout,
                TeamHolder.class,
                FirebaseUtils.getDatabase()
                        .child(Constants.FIREBASE_TEAM_INDEXES)
                        .child(FirebaseUtils.getUid()),
                FirebaseUtils.getDatabase().child(Constants.FIREBASE_TEAMS)) {
            @Override
            public void populateViewHolder(TeamHolder teamHolder, Team team, int position) {
                String teamNumber = team.getNumber();
                String key = getRef(position).getKey();

                teamHolder.setTeamNumber(teamNumber);
                teamHolder.setTeamName(team.getName(),
                                       TeamListActivity.this.getString(R.string.unknown_team));
                teamHolder.setTeamLogo(TeamListActivity.this, team.getMedia());
                teamHolder.setListItemClickListener(TeamListActivity.this, teamNumber, key);
                teamHolder.setCreateNewScoutListener(TeamListActivity.this, teamNumber, key);

                team.fetchLatestData(TeamListActivity.this, key);
            }

            @Override
            protected void onCancelled(DatabaseError databaseError) {
                FirebaseCrash.report(databaseError.toException());
            }
        };

        teams.setAdapter(mAdapter);

        if (savedInstanceState != null) {
            mAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override
                public void onItemRangeInserted(int positionStart, int itemCount) {
                    if (mAdapter.getItemCount() >= savedInstanceState.getInt(COUNT)) {
                        mManager.onRestoreInstanceState(savedInstanceState.getParcelable(
                                MANAGER_STATE));
                        mAdapter.unregisterAdapterDataObserver(this);
                    }
                }
            });
        }

        mAuthStateListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                if (firebaseAuth.getCurrentUser() != null) {
                    teams.setAdapter(mAdapter);
                } else {
                    teams.setAdapter(null);
                }
            }
        };
        FirebaseUtils.getAuth().addAuthStateListener(mAuthStateListener);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mAdapter != null) {
            outState.putParcelable(MANAGER_STATE, mManager.onSaveInstanceState());
            outState.putInt(COUNT, mAdapter.getItemCount());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mAuthStateListener != null) {
            FirebaseUtils.getAuth().removeAuthStateListener(mAuthStateListener);
        }
        if (mAdapter != null) {
            mAdapter.cleanup();
        }
    }

    @Override
    public void onBackPressed() {
//        if (mDrawer.isDrawerOpen()) {
//            mDrawer.closeDrawer();
//        } else {
        super.onBackPressed();
//        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.team_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_sign_in:
                startActivityForResult(
                        AuthUI.getInstance().createSignInIntentBuilder()
                                .setLogo(R.drawable.launch_logo_image)
                                .setProviders(
                                        Arrays.asList(new AuthUI.IdpConfig.Builder(AuthUI.EMAIL_PROVIDER)
                                                              .build(),
                                                      new AuthUI.IdpConfig.Builder(AuthUI.GOOGLE_PROVIDER)
                                                              .build(),
                                                      new AuthUI.IdpConfig.Builder(AuthUI.FACEBOOK_PROVIDER)
                                                              .build(),
                                                      new AuthUI.IdpConfig.Builder(AuthUI.TWITTER_PROVIDER)
                                                              .build()))
                                .build(),
                        RC_SIGN_IN);
                break;
            case R.id.action_sign_out:
                AuthUI.getInstance().signOut(this);
                break;
            case R.id.action_settings:
                break;
        }

        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == RC_SIGN_IN) {
            if (resultCode == RESULT_OK) {
                // user is signed in!
                Snackbar.make(findViewById(android.R.id.content),
                              R.string.successfully_signed_in,
                              Snackbar.LENGTH_LONG).show();

                DatabaseReference ref = FirebaseUtils.getDatabase()
                        .child("users")
                        .child(FirebaseUtils.getUid());
                ref.child("name").setValue(FirebaseUtils.getUser().getDisplayName());
                ref.child("provider").setValue(FirebaseUtils.getUser().getProviderId());
                ref.child("email").setValue(FirebaseUtils.getUser().getEmail());
            }
        }
    }
}
