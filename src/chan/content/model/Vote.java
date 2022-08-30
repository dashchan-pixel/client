package chan.content.model;

import chan.annotation.Public;

@Public
public final class Vote {

    private final int likes;
    private final int dislikes;
    private final boolean showVotes;

    @Public
    public Vote() {
        this.likes = 0;
        this.dislikes = 0;
        this.showVotes = false;
    }

    @Public
    public Vote(int likes, int dislikes) {
        this.likes = likes;
        this.dislikes = dislikes;
        this.showVotes = true;
    }

    @Public
    public int getLikes() {
        return likes;
    }

    @Public
    public int getDislikes() {
        return dislikes;
    }

    @Public
    public boolean isShowVotes() {
        return showVotes;
    }

}
