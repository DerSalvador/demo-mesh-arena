package demo.mesharena.ball;

import demo.mesharena.common.Commons;
import demo.mesharena.common.Point;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentracing.Span;
import io.opentracing.contrib.vertx.ext.web.TracingHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.micrometer.backends.BackendRegistries;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static demo.mesharena.common.Commons.*;

public class Ball extends AbstractVerticle {

  private static final long DELTA_MS = 100;
  private static final double RESISTANCE = Commons.getIntEnv("RESISTANCE", 80);
  private static final double PCT_ERRORS = Commons.getIntEnv("PCT_ERRORS", 0);
  private static final String IMAGE = Commons.getStringEnv("IMAGE", "ball");

  private final WebClient client;
  private final String id;
  private final Random rnd = new SecureRandom();
  private final JsonObject json;
  private final Optional<MeterRegistry> registry;

  private Point speed = Point.ZERO;
  private String controllingPlayer;
  private String controllingPlayerName;
  private int controllingPlayerSkill;
  private double controllingPlayerSkillTimer;
  private String controllingPlayerTeam;
  private Point pos = new Point(50, 50);
  private double interactTimer;
  private double errorTimer;

  private Ball(Vertx vertx) {
    Optional<Span> span = TRACER.map(t -> t.buildSpan("application_start").start());

    client = WebClient.create(vertx);
    id = "ball-" + UUID.randomUUID().toString();
    json = new JsonObject()
        .put("id", id)
        .put("style", "position: absolute; background-image: url(./" + IMAGE + ".png); width: 20px; height: 20px;"
            + "z-index: 5; transition: top " + DELTA_MS + "ms, left " + DELTA_MS + "ms;")
        .put("text", "");

    registry = Optional.ofNullable(BackendRegistries.getDefaultNow());
    registry.ifPresent(reg -> Gauge.builder("mesharena_ball_speed", () -> speed.size())
        .description("Ball speed gauge")
        .register(reg));
    if (!registry.isPresent()) {
      System.out.println("No metrics");
    }
    span.ifPresent(Span::finish);
  }

  public static void main(String[] args) {
    Vertx vertx = Commons.vertx();
    vertx.deployVerticle(new Ball(vertx));
  }

  @Override
  public void start() throws Exception {
    // Register ball API
    HttpServerOptions serverOptions = new HttpServerOptions().setPort(Commons.BALL_PORT);
    Router router = Router.router(vertx);
    TRACER.ifPresent(tracer -> {
      TracingHandler handler = new TracingHandler(tracer);
      router.route()
          .order(-1).handler(handler)
          .failureHandler(handler);
    });

    router.get("/health").handler(ctx -> ctx.response().end());
    router.put("/shoot").handler(this::shoot);
    router.get("/tryGet").handler(this::tryGet);
    router.put("/setPosition").handler(this::setPosition);
    vertx.createHttpServer().requestHandler(router)
        .listen(serverOptions.getPort(), serverOptions.getHost());

    // Ping-display
    vertx.setPeriodic(2000, loopId -> this.display());

    // Start game loop
    vertx.setPeriodic(DELTA_MS, loopId -> this.update((double)DELTA_MS / 1000.0));
  }

  private void tryGet(RoutingContext ctx) {
    ctx.request().bodyHandler(buf -> {
      if (rnd.nextInt(100) < PCT_ERRORS) {
        errorTimer = 0;
        ctx.response().setStatusCode(503).setStatusMessage("faiiiiilure! (to test outlier detection)").end();
        return;
      }
      interactTimer = 0;
      JsonObject input = buf.toJsonObject();
      double playerX = input.getDouble("playerX");
      double playerY = input.getDouble("playerY");
      String playerID = input.getString("playerID");
      int playerSkill = input.getInteger("playerSkill");
      String playerName = input.getString("playerName");
      String playerTeam = input.getString("playerTeam");
      JsonObject output = new JsonObject()
          .put("x", pos.x())
          .put("y", pos.y())
          .put("id", id);

      double distanceToBall = pos.diff(new Point(playerX, playerY)).size();
      if (distanceToBall < 15) {
        controllingPlayerSkill = playerSkill;
        if (playerID.equals(controllingPlayer)) {
          output.put("success", true);
        } else if (controllingPlayer == null || rnd.nextInt(2 * controllingPlayerSkill + playerSkill) < playerSkill) {
          if (controllingPlayer != null) {
            registry.ifPresent(reg -> Counter.builder("mesharena_take_ball")
                .description("Counter of player taking control of the ball")
                .tag("team", playerTeam)
                .tag("player", playerName)
                .register(reg)
                .increment());
          }
          controllingPlayer = playerID;
          controllingPlayerName = playerName;
          controllingPlayerTeam = playerTeam;
          output.put("success", true).put("takesBall", true);
        }
      }

      ctx.response().end(output.toString());
    });
  }

  private void shoot(RoutingContext ctx) {
    ctx.request().bodyHandler(buf -> {
      JsonObject json = buf.toJsonObject();
//      String playerID = json.getString("playerID");
      double dx = json.getDouble("dx");
      double dy = json.getDouble("dy");
      String kind = json.getString("kind");
      if (controllingPlayer != null) {
//      if (playerID.equals(controllingPlayer)) {
        speed = new Point(dx, dy);
        if ("togoal".equals(kind)) {
          if (rnd.nextInt(2) == 0) {
            comment(controllingPlayerName + " shooting!");
          } else {
            comment("Wooow " + controllingPlayerName + " tries his luck!");
          }
          registry.ifPresent(reg -> Counter.builder("mesharena_shoots")
              .description("Shoots counter")
              .tag("team", controllingPlayerTeam)
              .tag("player", controllingPlayerName)
              .register(reg)
              .increment());
        } else if ("forward".equals(kind)) {
          if (rnd.nextInt(2) == 0) {
            comment("Still " + controllingPlayerName + "...");
          } else {
            comment(controllingPlayerName + " again...");
          }
        } else if ("defensive".equals(kind)) {
          if (rnd.nextInt(2) == 0) {
            comment("Defensive shooting from " + controllingPlayerName);
          } else {
            comment(controllingPlayerName + " takes the ball and shoots!");
          }
        } else if ("control".equals(kind)) {
          comment(controllingPlayerName + " takes the ball back");
        }
      }
      ctx.response().end();
    });
  }

  private void comment(String text) {
    JsonObject json = new JsonObject()
        .put("id", "ball-comment")
        .put("style", "position: absolute; color: brown; font-weight: bold; z-index: 10; top: " + (pos.y() + 10) + "px; left: " + (pos.x() - 10) + "px;")
        .put("text", text);

    client.post(UI_PORT, UI_HOST, "/display").sendJson(json, ar -> {
      if (!ar.succeeded()) {
        ar.cause().printStackTrace();
      }
    });
  }

  private void setPosition(RoutingContext ctx) {
    ctx.request().bodyHandler(buf -> {
      JsonObject json = buf.toJsonObject();
      double x = json.getDouble("x");
      double y = json.getDouble("y");
      pos = new Point(x, y);
      speed = Point.ZERO;
      controllingPlayer = null;
      controllingPlayerSkill = 0;
      ctx.response().end();
      display();
    });
  }

  private void update(double delta) {
    double oldSpeed = speed.size();
    if (oldSpeed > 0) {
      Point oldPos = pos;
      Point newPos = pos.add(speed.mult(delta));
      double newSpeed = Math.max(0, oldSpeed - RESISTANCE * delta);
      checkBounce(pos, newPos, newSpeed, didBounce -> {
        if (!didBounce) {
          pos = newPos;
          speed = speed.mult(newSpeed / oldSpeed);
        }
        if (!oldPos.equals(pos)) {
          display();
        }
      });
    }
    // Decrease controlling skill
    if (controllingPlayerSkill > 0) {
      controllingPlayerSkillTimer += delta;
      if (controllingPlayerSkillTimer >= 0.5) {
        controllingPlayerSkill--;
        controllingPlayerSkillTimer = 0;
      }
    }
    interactTimer += delta;
    errorTimer += delta;
  }

  private void checkBounce(Point oldPos, Point newPos, double newSpeed, Handler<Boolean> handler) {
    JsonObject json = new JsonObject()
        .put("xStart", oldPos.x())
        .put("yStart", oldPos.y())
        .put("xEnd", newPos.x())
        .put("yEnd", newPos.y());

    HttpRequest<Buffer> request = client.post(STADIUM_PORT, STADIUM_HOST, "/bounce");
    request.sendJson(json, ar -> {
      if (!ar.succeeded()) {
        // No stadium => no bounce
        handler.handle(false);
      } else {
        HttpResponse<Buffer> response = ar.result();
        JsonObject obj = response.bodyAsJsonObject();
        if (obj.containsKey("scored")) {
          String team = obj.getString("scored");
          boolean isOwn = !team.equals(controllingPlayerTeam);
          if (isOwn) {
            comment("Ohhhh own goal from " + controllingPlayerName + " !!");
          } else {
            comment("Goaaaaaaal by " + controllingPlayerName + " !!!!");
          }
          registry.ifPresent(reg -> Counter.builder("mesharena_goals")
              .description("Goals counter")
              .tag("team", team)
              .tag("player", controllingPlayerName)
              .tag("own_goal", isOwn ? "yes" : "no")
              .register(reg)
              .increment());
          // Do not update position, Stadium will do it
          controllingPlayer = null;
          speed = Point.ZERO;
          handler.handle(true);
        } else if (obj.containsKey("x")) {
          // Contains bounce data
          double x = obj.getDouble("x");
          double y = obj.getDouble("y");
          pos = new Point(x, y);
          double normDx = obj.getDouble("dx");
          double normDy = obj.getDouble("dy");
          speed = new Point(normDx * newSpeed, normDy * newSpeed);
          handler.handle(true);
        } else {
          handler.handle(false);
        }
      }
    });
  }

  private void display() {
    json.put("x", pos.x() - 10)
        .put("y", pos.y() - 10)
        .put("style", getStyle());

    client.post(UI_PORT, UI_HOST, "/display").sendJson(json, ar -> {
      if (!ar.succeeded()) {
        ar.cause().printStackTrace();
      }
    });
  }

  private String getStyle() {
    final String filters;
    if (errorTimer < 3d) {
      // filter: brightness(40%) sepia(100%) hue-rotate(-50deg) saturate(600%);
      // error ]0 (no err), 1 (err)]
      double error = 1d - errorTimer / 3d;
      // hue rotate ]0 (no err), -50 (err)]
      int hue = (int)(-50 * error);
      // brightness [40 (err), 100 (no err)[
      int brightness = 40 + (int)(60d * (1d - error));
      // sepia [0 (no err), 100 (err)[
      int sepia = (int)(100d * error);
      // saturate ]100 (no err), 600 (err)]
      int saturate = 100 + (int)(500d * error);
      filters = "filter: brightness(" + brightness + "%) sepia(" + sepia + "%) hue-rotate(" + hue + "deg) saturate(" + saturate + "%);"
          + "-webkit-filter: brightness(" + brightness + "%) sepia(" + sepia + "%) hue-rotate(" + hue + "deg) saturate(" + saturate + "%);";
    } else {
      // interact [0 (old), 1 (now)]
      double interact = 1d - Math.min(interactTimer, 3d) / 3d;
      // brightness [40 (old), 100 (now)]
      int brightness = 40 + (int)(60d * interact);
      filters = "filter: brightness(" + brightness + "%); -webkit-filter: brightness(" + brightness + "%);";
    }

    return "position: absolute; background-image: url(./" + IMAGE + ".png); width: 20px; height: 20px;"
        + "z-index: 5; transition: top " + DELTA_MS + "ms, left " + DELTA_MS + "ms;"
        + filters;
  }
}
