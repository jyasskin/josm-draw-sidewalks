package info.yasskin.jeffrey.josm.drawsidewalks;

import java.util.ArrayDeque;
import java.util.Deque;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;

class UndoRedoBuilder implements AutoCloseable {
  private final Deque<Command> commands;
  boolean failed = false;

  UndoRedoBuilder() {
    commands = new ArrayDeque<>();
  }

  public boolean add(Command c) {
    if (failed)
      return false;
    if (c.executeCommand()) {
      commands.addLast(c);
      return true;
    }
    failed = true;
    return false;
  }

  public void commit(String description) {
    if (failed)
      return;
    // All commands were already executed, so the UndoRedoHandler shouldn't do it again.
    UndoRedoHandler.getInstance().add(SequenceCommand.wrapIfNeeded(description, commands), /*execute=*/false);
    commands.clear();
  }

  @Override
  public void close() {
    while (!commands.isEmpty()) {
      commands.removeLast().undoCommand();
    }
    failed = false;
  }
}
