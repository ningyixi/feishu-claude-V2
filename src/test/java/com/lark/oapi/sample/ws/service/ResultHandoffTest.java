package com.lark.oapi.sample.ws.service;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class ResultHandoffTest {
    @Test void usageIsAvailableBeforeTheCallerIsReleased() throws Exception {
        var turnClass=Class.forName(TerminalSession.class.getName()+"$Turn");var ctor=turnClass.getDeclaredConstructor(TurnListener.class);ctor.setAccessible(true);
        var futureField=turnClass.getDeclaredField("future");futureField.setAccessible(true);
        AtomicReference<Object> turnRef=new AtomicReference<>();AtomicReference<TurnResult> usage=new AtomicReference<>();
        var listener=new SupplierWikiService.QuietListener(){
            @Override public void onResultAvailable(TurnResult result){try{assertFalse(((CompletableFuture<?>)futureField.get(turnRef.get())).isDone());}catch(IllegalAccessException e){throw new AssertionError(e);}usage.set(result);}
            @Override public void onTurnComplete(TurnResult result){assertSame(result,usage.get());}
        };
        Object turn=ctor.newInstance(listener);turnRef.set(turn);var session=new TerminalSession("usage-test","","");
        var current=TerminalSession.class.getDeclaredField("currentTurn");current.setAccessible(true);current.set(session,turn);
        var result=TerminalSession.class.getDeclaredMethod("handleResult",JsonObject.class);result.setAccessible(true);
        try{result.invoke(session,JsonParser.parseString("{\"result\":\"answer\",\"usage\":{\"input_tokens\":31,\"output_tokens\":9,\"cache_read_input_tokens\":400}}").getAsJsonObject());
            assertTrue(((CompletableFuture<?>)futureField.get(turn)).isDone());assertEquals(31,usage.get().inputTokens);assertEquals(400,usage.get().cacheReadTokens);
        }finally{session.destroy();}
    }
}
