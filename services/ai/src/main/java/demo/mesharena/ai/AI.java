package demo.mesharena.ai;

import demo.mesharena.common.Commons;
import demo.mesharena.common.Point;
import demo.mesharena.common.Segment;
import demo.mesharena.common.TracingContext;
import io.opentracing.References;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer.SpanBuilder;
import io.opentracing.propagation.Format.Builtin;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.micrometer.PrometheusScrapingHandler;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static demo.mesharena.common.Commons.*;

public class AI extends AbstractVerticle {

  private static final long DELTA_MS = 300;
  private static final double IDLE_TIMER = 2.0;
  private static final double ROLE_TIMER = 10.0;
  private static final String NAME = Commons.getStringEnv("PLAYER_NAME", "Goat");
  private static final String COLOR = Commons.getStringEnv("PLAYER_COLOR", "blue");
  private static final String TEAM = Commons.getStringEnv("PLAYER_TEAM", "locals");
  // Speed = open scale
  private static final double SPEED = Commons.getIntEnv("PLAYER_SPEED", 60);
  // Accuracy [0, 1]
  private static final double ACCURACY = Commons.getDoubleEnv("PLAYER_ACCURACY", 0.8);
  private static final double MIN_SPEED = ACCURACY * SPEED;
  // Skill = open scale
  private static final int SKILL = Commons.getIntEnv("PLAYER_SKILL", 5);
  // Shoot strength = open scale
  private static final double SHOOT_STRENGTH = Commons.getIntEnv("PLAYER_SHOOT", 250);
  // Attacking / defending? (more is attacking) [0, 100]
  private static final int ATTACKING = Commons.getIntEnv("PLAYER_ATTACKING", 65);
  // While attacking, will shoot quickly? [0, 100]
  private static final int ATT_SHOOT_FAST = Commons.getIntEnv("PLAYER_ATT_SHOOT_FAST", 20);
  // While defending, will shoot quickly? [0, 100]
  private static final int DEF_SHOOT_FAST = Commons.getIntEnv("PLAYER_DEF_SHOOT_FAST", 40);

  private final Random rnd = new SecureRandom();
  private final WebClient client;
  private final String id;
  private final boolean isVisitors;
  private final JsonObject json;
  private Point pos = Point.ZERO;
  private ArenaInfo arenaInfo;
  private Point currentDestination = null;
  private Point defendPoint = null;
  private double idleTimer = -1;
  private double roleTimer = -1;

  private enum Role { ATTACK, DEFEND }
  private Role role;

  private Optional<Span> currentSpan = Optional.empty();
  private Optional<Span> newGameSpan = Optional.empty();

  public AI(Vertx vertx) {
    Optional<Span> span = TRACER.map(t -> t.buildSpan("application_start").start());

    client = WebClient.create(vertx);
    id = UUID.randomUUID().toString();
    this.isVisitors = !TEAM.equals("locals");
    json = new JsonObject()
        .put("id", id)
        .put("style", "position: absolute; background-color: " + COLOR + "; transition: top " + DELTA_MS + "ms, left " + DELTA_MS + "ms; height: 30px; width: 30px; border-radius: 50%; z-index: 8;")
        .put("text", "");
    span.ifPresent(Span::finish);
  }

  public static void main(String[] args) {
    Vertx vertx = Commons.vertx();
    vertx.deployVerticle(new AI(vertx));
  }

  @Override
  public void start() throws Exception {
    // Start metrics server
    HttpServerOptions serverOptions = new HttpServerOptions().setPort(8080);

    Router router = Router.router(vertx);
    router.get("/health").handler(ctx -> ctx.response().end());

    if (Commons.METRICS_ENABLED == 1) {
      router.route("/metrics").handler(PrometheusScrapingHandler.create());
    }
    vertx.createHttpServer().requestHandler(router)
        .listen(serverOptions.getPort(), serverOptions.getHost());

    // First display
    Optional<Span> startSpan = TRACER.map(t -> t.buildSpan("start").start());
    display(startSpan.map(Span::context));

    // Check regularly about arena info
    checkArenaInfo();
    vertx.setPeriodic(5000, loopId -> checkArenaInfo());

    // Start game loop
    vertx.setPeriodic(DELTA_MS, loopId -> update((double)DELTA_MS / 1000.0));
    startSpan.ifPresent(Span::finish);
  }

  private void checkArenaInfo() {
    client.get(STADIUM_PORT, STADIUM_HOST, "/info").sendJson(
        new JsonObject().put("isVisitors", isVisitors),
        ar -> {
          if (!ar.succeeded()) {
            ar.cause().printStackTrace();
            arenaInfo = null;
          } else {
            HttpResponse<Buffer> response = ar.result();
            JsonObject obj = response.bodyAsJsonObject();
            double goalX = obj.getDouble("goalX");
            double goalY = obj.getDouble("goalY");
            int defendZoneTop = obj.getInteger("defendZoneTop");
            int defendZoneBottom = obj.getInteger("defendZoneBottom");
            int defendZoneLeft = obj.getInteger("defendZoneLeft");
            int defendZoneRight = obj.getInteger("defendZoneRight");
            int scoreA = obj.getInteger("scoreA");
            int scoreB = obj.getInteger("scoreB");

            if (arenaInfo == null || (arenaInfo.scoreA != scoreA || arenaInfo.scoreA != scoreB)) {
              // the goals changed - new game started
              newGameSpan = Optional.empty();
              TRACER.ifPresent(tracer -> {
                try (Scope startGame = tracer.buildSpan("new_game")
                    .withTag("name", NAME)
                    .withTag("id", id)
                    .ignoreActiveSpan()
                    .startActive(true)) {
                  newGameSpan = Optional.of(startGame.span());
                }
              });
            }

            arenaInfo = new ArenaInfo(defendZoneTop, defendZoneLeft, defendZoneBottom, defendZoneRight, new Point(goalX, goalY), scoreA, scoreB);
          }
        });
  }

  private void update(double delta) {
    Optional<Span> updateSpan = TRACER.map(tracer -> {
      SpanBuilder updateSpanBuilder = tracer.buildSpan("update")
          .withTag("x", pos != null ? pos.x() :  0)
          .withTag("x", pos != null ? pos.y() : 0)
          .withTag("role", role != null ? role.name(): "unknown");
      SpanContext followsFrom = newGameSpan.map(Span::context).orElse(currentSpan.map(Span::context).orElse(null));
      newGameSpan = Optional.empty();
      updateSpanBuilder.addReference(References.FOLLOWS_FROM, followsFrom);
      return updateSpanBuilder.start();
    });

    if (idleTimer > 0) {
      idleTimer -= delta;
      walkRandom(updateSpan.map(Span::context), delta);
    } else {
      roleTimer -= delta;
      if (roleTimer < 0) {
        roleTimer = ROLE_TIMER;
        chooseRole();
      }
      lookForBall(updateSpan.map(Span::context), delta);
    }

    updateSpan.ifPresent(Span::finish);
    currentSpan = updateSpan;
  }

  private void chooseRole() {
    if (rnd.nextInt(100) > ATTACKING) {
      role = Role.DEFEND;
      if (arenaInfo == null) {
        defendPoint = randomDestination();
      } else {
        Point dimension = arenaInfo.defendZoneTLBR.derivate();
        defendPoint = new Point(rnd.nextInt((int) dimension.x()), rnd.nextInt((int) dimension.y()))
          .add(arenaInfo.defendZoneTLBR.start());
      }
    } else {
      role = Role.ATTACK;
    }
  }

  private void lookForBall(Optional<SpanContext> spanContext, double delta) {
    JsonObject json = new JsonObject()
        .put("playerX", pos.x())
        .put("playerY", pos.y())
        .put("playerSkill", SKILL)
        .put("playerID", id)
        .put("playerName", NAME)
        .put("playerTeam", TEAM);

    HttpRequest<Buffer> request = client.get(BALL_PORT, BALL_HOST, "/tryGet");
    spanContext.ifPresent(sc -> TRACER.ifPresent(tracer -> tracer.inject(sc, Builtin.HTTP_HEADERS, new TracingContext(request.headers()))));
    request.sendJson(json, ar -> {
      if (!ar.succeeded()) {
        // No ball? What a pity. Walk randomly in sadness.
        idle();
      } else {
        HttpResponse<Buffer> response = ar.result();
        JsonObject obj = response.bodyAsJsonObject();
        if (obj != null) {
          double x = obj.getDouble("x");
          double y = obj.getDouble("y");
          Point ball = new Point(x, y);
          if (role == Role.ATTACK || pos.diff(ball).size() < 70) {
            // Go to the ball
            currentDestination = ball;
          } else {
            currentDestination = defendPoint;
          }
          if (Boolean.TRUE.equals(obj.getBoolean("success"))) {
            shoot(spanContext, Boolean.TRUE.equals(obj.getBoolean("takesBall")));
          }
        }
        walkToDestination(spanContext, delta);
      }
    });
  }

  private void shoot(Optional<SpanContext> spanContext, boolean takesBall) {
    final Point shootVector;
    final String kind;
    Point goal = (arenaInfo == null) ? randomDestination() : arenaInfo.goal;
    if (role == Role.ATTACK) {
      Point direction = randomishSegmentNormalized(new Segment(pos, goal));
      // Go forward or try to shoot
      int rndNum = rnd.nextInt(100);
      if (rndNum < ATT_SHOOT_FAST) {
        // Try to shoot (if close enough to ball)
        shootVector = direction.mult(SHOOT_STRENGTH);
        kind = "togoal";
      } else {
        // Go forward
        shootVector = direction.mult(SPEED * 1.8);
        kind = takesBall ? "control" : "forward";
      }
    } else {
      // Defensive shoot
      Point direction = randomishSegmentNormalized(new Segment(pos, goal));
      // Go forward or defensive shoot
      int rndNum = rnd.nextInt(100);
      if (rndNum < DEF_SHOOT_FAST) {
        // Defensive shoot, randomise a second time, shoot stronger
        direction = randomishSegmentNormalized(new Segment(pos, pos.add(direction)));
        shootVector = direction.mult(SHOOT_STRENGTH * 1.5);
        kind = "defensive";
      } else {
        // Go forward
        shootVector = direction.mult(SPEED * 1.8);
        kind = takesBall ? "control" : "forward";
      }
    }
    JsonObject json = new JsonObject()
        .put("dx", shootVector.x())
        .put("dy", shootVector.y())
        .put("kind", kind)
        .put("playerID", id);

    HttpRequest<Buffer> request = client.put(BALL_PORT, BALL_HOST, "/shoot");
    spanContext.ifPresent(sc -> TRACER.ifPresent(tracer -> tracer.inject(sc, Builtin.HTTP_HEADERS, new TracingContext(request.headers()))));
    request.sendJson(json, ar -> {});
  }

  private void idle() {
    idleTimer = IDLE_TIMER;
  }

  private Point randomDestination() {
    return new Point(rnd.nextInt(500), rnd.nextInt(500));
  }

  private void walkRandom(Optional<SpanContext> spanContext, double delta) {
    if (currentDestination == null || new Segment(pos, currentDestination).size() < 10) {
      currentDestination = randomDestination();
    }
    walkToDestination(spanContext, delta);
  }

  private void walkToDestination(Optional<SpanContext> spanContext, double delta) {
    if (currentDestination != null) {
      // Speed and angle are modified by accuracy
      Segment segToDest = new Segment(pos, currentDestination);
      // maxSpeed avoids stepping to high when close to destination
      double maxSpeed = Math.min(segToDest.size() / delta, SPEED);
      // minSpeed must be kept <= maxSpeed
      double minSpeed = Math.min(maxSpeed, MIN_SPEED);
      double speed = delta * (minSpeed + rnd.nextDouble() * (maxSpeed - minSpeed));
      Point relativeMove = randomishSegmentNormalized(segToDest).mult(speed);
      pos = pos.add(relativeMove);
      display(spanContext);
    }
  }

  private Point randomishSegmentNormalized(Segment segToDest) {
    double angle = rnd.nextDouble() * (1.0 - ACCURACY) * Math.PI;
    if (rnd.nextInt(2) == 0) {
      angle *= -1;
    }
    return segToDest.derivate().normalize().rotate(angle);
  }

  private void display(Optional<SpanContext> spanContext) {
    json.put("x", pos.x() - 15)
        .put("y", pos.y() - 15);

    HttpRequest<Buffer> request = client.post(UI_PORT, UI_HOST, "/display");
    spanContext.ifPresent(sc -> TRACER.ifPresent(tracer -> tracer.inject(sc, Builtin.HTTP_HEADERS, new TracingContext(request.headers()))));
    request.sendJson(json, ar -> {
      if (!ar.succeeded()) {
        ar.cause().printStackTrace();
      }
    });
  }

  private static class ArenaInfo {
    private final Segment defendZoneTLBR;
    private final Point goal;
    private final int scoreA;
    private final int scoreB;

    private ArenaInfo(int defendZoneTop, int defendZoneLeft, int defendZoneBottom, int defendZoneRight, Point goal, int scoreA, int scoreB) {
      this.defendZoneTLBR = new Segment(new Point(defendZoneLeft, defendZoneTop), new Point(defendZoneRight, defendZoneBottom));
      this.goal = goal;
      this.scoreA = scoreA;
      this.scoreB = scoreB;
    }
  }
}
