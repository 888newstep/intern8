package vip.xiaozhao.intern.baseUtil.intf.entity;

import java.util.Date;


public class TuiNovel {

  private int id;
  private String name;
  private int authorId;
  private String authorName;
  private int status;
  private int type;
  private Date latestUpdateTime;
  private String chapterName;
  private String cover;
  private int subscribeNum;
  private Date addTime;
  private Date updateTime;

  //[bean] get set


    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAuthorId() {
        return authorId;
    }

    public void setAuthorId(int authorId) {
        this.authorId = authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public Date getLatestUpdateTime() {
        return latestUpdateTime;
    }

    public void setLatestUpdateTime(Date latestUpdateTime) {
        this.latestUpdateTime = latestUpdateTime;
    }

    public String getChapterName() {
        return chapterName;
    }

    public void setChapterName(String chapterName) {
        this.chapterName = chapterName;
    }

    public String getCover() {
        return cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
    }

    public int getSubscribeNum() {
        return subscribeNum;
    }

    public void setSubscribeNum(int subscribeNum) {
        this.subscribeNum = subscribeNum;
    }

    public Date getAddTime() {
        return addTime;
    }

    public void setAddTime(Date addTime) {
        this.addTime = addTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "TuiNovel{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", authorId=" + authorId +
                ", authorName='" + authorName + '\'' +
                ", status=" + status +
                ", type=" + type +
                ", latestUpdateTime=" + latestUpdateTime +
                ", chapterName='" + chapterName + '\'' +
                ", cover='" + cover + '\'' +
                ", subscribeNum=" + subscribeNum +
                ", addTime=" + addTime +
                ", updateTime=" + updateTime +
                '}';
    }
}
