package dev.zfold.assistant;

/** Run with javac/java alongside SlashCommands.java; no Android dependency. */
public final class SlashCommandsCheck {
  public static void main(String[] args){
    String[] parsed=new String[2];
    if(!SlashCommands.parse(" /goal ",parsed)||!parsed[0].equals("goal")||!parsed[1].isEmpty())throw new AssertionError("goal parsing");
    if(SlashCommands.filter("/").stream().noneMatch(c->c.name.equals("goal")))throw new AssertionError("goal discoverability");
    if(SlashCommands.filter("/go").stream().noneMatch(c->c.name.equals("goal")))throw new AssertionError("prefix matching");
    if(SlashCommands.filter("/目标").stream().noneMatch(c->c.name.equals("goal")))throw new AssertionError("Chinese matching");
    if(SlashCommands.parse("/unknown",parsed))throw new AssertionError("unknown command");
    System.out.println("Slash command checks passed");
  }
}
